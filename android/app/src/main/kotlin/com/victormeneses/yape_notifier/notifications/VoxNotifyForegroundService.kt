package com.victormeneses.yape_notifier.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.victormeneses.yape_notifier.MainActivity
import com.victormeneses.yape_notifier.R
import com.victormeneses.yape_notifier.nativebridge.NotificationAccess
import com.victormeneses.yape_notifier.storage.ListenerDiagnosticsRepository
import com.victormeneses.yape_notifier.storage.NativeSettingsRepository
import com.victormeneses.yape_notifier.storage.SharedPreferencesStore
import com.victormeneses.yape_notifier.storage.VoxNotifyEventBus

class VoxNotifyForegroundService : Service() {
    private lateinit var diagnosticsRepository: ListenerDiagnosticsRepository
    private lateinit var settingsRepository: NativeSettingsRepository
    private lateinit var rebindScheduler: ListenerRebindScheduler
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private var lastListenerRecoveryAttemptAt = 0L

    private val heartbeat = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            lastHeartbeatAt = now
            diagnosticsRepository.update(
                mapOf(
                    "foregroundSpeechServiceRunning" to "true",
                    "foregroundHeartbeatAt" to now.toString(),
                ),
            )
            recoverListenerIfNeeded(now)
            refreshPersistentStatusNotification()
            heartbeatHandler.postDelayed(this, HEARTBEAT_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        val store = SharedPreferencesStore(applicationContext)
        diagnosticsRepository = ListenerDiagnosticsRepository(store)
        settingsRepository = NativeSettingsRepository(store)
        rebindScheduler = ListenerRebindScheduler(applicationContext, diagnosticsRepository)
        createChannel()
        activeInstance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            disableAndStop()
            return START_NOT_STICKY
        }
        if (intent == null && !settingsRepository.get().continuousBackground) {
            heartbeatHandler.removeCallbacks(heartbeat)
            diagnosticsRepository.update(
                mapOf(
                    "foregroundSpeechServiceRunning" to "false",
                    "foregroundServiceStoppedAt" to System.currentTimeMillis().toString(),
                    "lastProcessingResult" to "foreground_sticky_ignored_pref_false",
                ),
            )
            running = false
            lastHeartbeatAt = 0L
            stopSelf(startId)
            return START_NOT_STICKY
        }
        runCatching {
            startMonitoringForeground()
        }.onFailure { error ->
            diagnosticsRepository.update(
                mapOf(
                    "foregroundSpeechServiceRunning" to "false",
                    "lastProcessingResult" to "foreground_start_failed:${error.javaClass.simpleName}",
                ),
            )
            running = false
            throw error
        }
        running = true
        lastHeartbeatAt = System.currentTimeMillis()
        diagnosticsRepository.update(
            mapOf(
                "foregroundSpeechServiceRunning" to "true",
                "foregroundServiceStartedAt" to lastHeartbeatAt.toString(),
                "foregroundHeartbeatAt" to lastHeartbeatAt.toString(),
                "lastProcessingResult" to "foreground_service_start",
            ),
        )
        heartbeatHandler.removeCallbacks(heartbeat)
        heartbeatHandler.post(heartbeat)
        VoxNotifyEventBus.emit("foreground_service_changed")
        recoverListenerIfNeeded(System.currentTimeMillis(), immediate = true)
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        diagnosticsRepository.update(mapOf("lastProcessingResult" to "foreground_onTaskRemoved"))
        VoxNotifyEventBus.emit("foreground_service_changed")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        heartbeatHandler.removeCallbacksAndMessages(null)
        diagnosticsRepository.update(
            mapOf(
                "foregroundSpeechServiceRunning" to "false",
                "foregroundServiceStoppedAt" to System.currentTimeMillis().toString(),
            ),
        )
        running = false
        lastHeartbeatAt = 0L
        if (activeInstance === this) activeInstance = null
        if (::settingsRepository.isInitialized && !settingsRepository.get().continuousBackground) {
            showPersistentStatusNotification(applicationContext, Status.DISABLED)
        }
        VoxNotifyEventBus.emit("foreground_service_changed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * The monitoring service remains specialUse for its entire lifetime. Spoken announcements
     * are routed as notification audio by YapeTextToSpeech and do not depend on mediaPlayback
     * foreground-service capabilities or AudioFocus.
     */
    private fun startMonitoringForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification())
        }
        diagnosticsRepository.update(
            mapOf(
                "foregroundServiceType" to "specialUse",
                "foregroundAudioCapability" to "notification_audio_no_focus",
            ),
        )
    }

    private fun refreshPersistentStatusNotification() {
        val settings = settingsRepository.get()
        val status = when {
            !settings.continuousBackground || !settings.voiceEnabled -> Status.DISABLED
            !NotificationAccess.isEnabled(applicationContext) -> Status.NEEDS_ATTENTION
            else -> Status.ACTIVE
        }
        getSystemService(NotificationManager::class.java).notify(
            NOTIFICATION_ID,
            buildStatusNotification(applicationContext, status),
        )
    }

    private fun disableAndStop() {
        settingsRepository.update(settingsRepository.get().copy(continuousBackground = false))
        heartbeatHandler.removeCallbacksAndMessages(null)
        diagnosticsRepository.update(
            mapOf(
                "foregroundSpeechServiceRunning" to "false",
                "foregroundServiceStoppedAt" to System.currentTimeMillis().toString(),
                "lastProcessingResult" to "foreground_disabled_by_user",
            ),
        )
        running = false
        lastHeartbeatAt = 0L
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_DETACH)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(false)
        }
        showPersistentStatusNotification(applicationContext, Status.DISABLED)
        VoxNotifyEventBus.emit("foreground_service_changed")
        stopSelf()
    }

    private fun recoverListenerIfNeeded(now: Long, immediate: Boolean = false) {
        if (!NotificationAccess.isEnabled(applicationContext)) {
            diagnosticsRepository.update(
                mapOf(
                    "listenerRecoveryState" to "notification_access_disabled",
                    "listenerRecoveryReason" to "watchdog",
                ),
            )
            return
        }

        val diagnostics = diagnosticsRepository.get()
        val connected = diagnostics["listenerConnected"] == "true"
        val heartbeatAt = listOf(
            diagnostics["listenerHeartbeatAt"].orEmpty().toLongOrNull() ?: 0L,
            diagnostics["listenerHealthCheckAt"].orEmpty().toLongOrNull() ?: 0L,
            diagnostics["lastCallbackAt"].orEmpty().toLongOrNull() ?: 0L,
        ).maxOrNull() ?: 0L
        val heartbeatStale = heartbeatAt <= 0L || now - heartbeatAt > LISTENER_HEARTBEAT_STALE_MS

        if (connected && !heartbeatStale) {
            if (diagnostics["listenerRecoveryState"] != "healthy") {
                diagnosticsRepository.update(
                    mapOf(
                        "listenerRecoveryState" to "healthy",
                        "listenerRecoveryReason" to "watchdog",
                    ),
                )
            }
            return
        }

        if (!immediate && now - lastListenerRecoveryAttemptAt < LISTENER_RECOVERY_COOLDOWN_MS) return
        lastListenerRecoveryAttemptAt = now
        val reason = when {
            !connected -> "watchdog_disconnected"
            heartbeatAt <= 0L -> "watchdog_no_heartbeat"
            else -> "watchdog_stale_heartbeat_${now - heartbeatAt}ms"
        }
        // If diagnostics still claim "connected" but the health check stopped, force a clean
        // unbind/rebind cycle on Android 14+. If it is already disconnected, a normal rebind is enough.
        rebindScheduler.recover(reason, forceCycle = connected && heartbeatStale)
    }

    private fun notification(): Notification {
        val settings = settingsRepository.get()
        val status = when {
            !settings.continuousBackground || !settings.voiceEnabled -> Status.DISABLED
            !NotificationAccess.isEnabled(applicationContext) -> Status.NEEDS_ATTENTION
            else -> Status.ACTIVE
        }
        return buildStatusNotification(applicationContext, status)
    }

    private fun createChannel() {
        ensureChannel(applicationContext)
    }

    private enum class Status {
        ACTIVE,
        NEEDS_ATTENTION,
        DISABLED,
    }

    companion object {
        private const val CHANNEL_ID = "voxnotify_status_persistent_v2"
        private const val LEGACY_CHANNEL_ID = "voxnotify_background"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_STOP = "com.victormeneses.yape_notifier.STOP_BACKGROUND"
        private const val HEARTBEAT_INTERVAL_MS = 15_000L
        private const val HEARTBEAT_STALE_MS = 45_000L
        private const val LISTENER_HEARTBEAT_STALE_MS = 75_000L
        private const val LISTENER_RECOVERY_COOLDOWN_MS = 60_000L
        @Volatile private var running = false
        @Volatile private var lastHeartbeatAt = 0L
        @Volatile private var activeInstance: VoxNotifyForegroundService? = null

        fun start(context: Context) {
            val intent = Intent(context, VoxNotifyForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val appContext = context.applicationContext
            val instance = activeInstance
            if (instance != null) {
                instance.disableAndStop()
                return
            }
            showPersistentStatusNotification(appContext, Status.DISABLED)
            appContext.stopService(Intent(appContext, VoxNotifyForegroundService::class.java))
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val manager = context.getSystemService(NotificationManager::class.java)
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Estado permanente de VoxNotify",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Indicador permanente de si VoxNotify está activo o necesita atención"
                    // The status must stay visible without producing an extra sound or vibration.
                    setSound(null, null)
                    enableVibration(false)
                    vibrationPattern = null
                    enableLights(false)
                    setShowBadge(false)
                    lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                }
                manager.createNotificationChannel(channel)

                // Do not post anything else to the old low-importance channel. It is kept only
                // as legacy channel metadata so we do not override a user's previous choices.
                manager.getNotificationChannel(LEGACY_CHANNEL_ID)
            }
        }

        private fun buildStatusNotification(context: Context, status: Status): Notification {
            ensureChannel(context)
            val openIntent = PendingIntent.getActivity(
                context,
                1,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(context, CHANNEL_ID)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(context)
            }
            val (title, text) = when (status) {
                Status.ACTIVE -> "VoxNotify está activo" to "Escuchando las aplicaciones seleccionadas"
                Status.NEEDS_ATTENTION -> "VoxNotify necesita atención" to "Activa el acceso a notificaciones para seguir escuchando"
                Status.DISABLED -> "VoxNotify está desactivado" to "Abre VoxNotify para volver a activar la lectura"
            }
            builder
                .setSmallIcon(R.drawable.ic_stat_voxnotify)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(openIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setCategory(Notification.CATEGORY_SERVICE)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android 12+ may defer foreground-service notifications. For VoxNotify the
                // notification is the user's always-visible health indicator, so request
                // immediate display even when the service was restored from boot.
                builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            }

            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                builder.setPriority(Notification.PRIORITY_DEFAULT)
            }

            return builder.build()
        }

        private fun showPersistentStatusNotification(context: Context, status: Status) {
            runCatching {
                context.getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    buildStatusNotification(context, status),
                )
            }
        }

        fun showCurrentStatus(context: Context) {
            val appContext = context.applicationContext
            val store = SharedPreferencesStore(appContext)
            val settings = NativeSettingsRepository(store).get()
            val status = when {
                !settings.continuousBackground || !settings.voiceEnabled -> Status.DISABLED
                !NotificationAccess.isEnabled(appContext) -> Status.NEEDS_ATTENTION
                else -> Status.ACTIVE
            }
            showPersistentStatusNotification(appContext, status)
        }

        fun isRunning(): Boolean =
            running && lastHeartbeatAt > 0L && System.currentTimeMillis() - lastHeartbeatAt <= HEARTBEAT_STALE_MS

    }
}
