package com.victormeneses.yape_notifier.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.victormeneses.yape_notifier.MainActivity
import com.victormeneses.yape_notifier.storage.ListenerDiagnosticsRepository
import com.victormeneses.yape_notifier.storage.NativeSettingsRepository
import com.victormeneses.yape_notifier.storage.SharedPreferencesStore
import com.victormeneses.yape_notifier.storage.VoxNotifyEventBus

class VoxNotifyForegroundService : Service() {
    private lateinit var diagnosticsRepository: ListenerDiagnosticsRepository
    private lateinit var settingsRepository: NativeSettingsRepository

    override fun onCreate() {
        super.onCreate()
        val store = SharedPreferencesStore(applicationContext)
        diagnosticsRepository = ListenerDiagnosticsRepository(store)
        settingsRepository = NativeSettingsRepository(store)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            settingsRepository.update(settingsRepository.get().copy(continuousBackground = false))
            diagnosticsRepository.update(
                mapOf(
                    "foregroundSpeechServiceRunning" to "false",
                    "lastProcessingResult" to "foreground_stop_action",
                ),
            )
            running = false
            VoxNotifyEventBus.emit("foreground_service_changed")
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent == null && !settingsRepository.get().continuousBackground) {
            diagnosticsRepository.update(
                mapOf(
                    "foregroundSpeechServiceRunning" to "false",
                    "lastProcessingResult" to "foreground_sticky_ignored_pref_false",
                ),
            )
            running = false
            stopSelf(startId)
            return START_NOT_STICKY
        }
        runCatching {
            startForeground(NOTIFICATION_ID, notification())
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
        diagnosticsRepository.update(
            mapOf(
                "foregroundSpeechServiceRunning" to "true",
                "lastProcessingResult" to "foreground_service_start",
            ),
        )
        VoxNotifyEventBus.emit("foreground_service_changed")
        ListenerRebindScheduler(applicationContext, diagnosticsRepository).request("foreground_service_start")
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        diagnosticsRepository.update(mapOf("lastProcessingResult" to "foreground_onTaskRemoved"))
        VoxNotifyEventBus.emit("foreground_service_changed")
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        diagnosticsRepository.update(mapOf("foregroundSpeechServiceRunning" to "false"))
        running = false
        VoxNotifyEventBus.emit("foreground_service_changed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopIntent = PendingIntent.getService(
            this,
            2,
            Intent(this, VoxNotifyForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("VoxNotify está activo")
            .setContentText("Escuchando las aplicaciones seleccionadas")
            .setContentIntent(openIntent)
            .setOngoing(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Detener", stopIntent)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Lectura continua",
                NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "voxnotify_background"
        private const val NOTIFICATION_ID = 4201
        private const val ACTION_STOP = "com.victormeneses.yape_notifier.STOP_BACKGROUND"
        @Volatile private var running = false

        fun start(context: Context) {
            val intent = Intent(context, VoxNotifyForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, VoxNotifyForegroundService::class.java))
        }

        fun isRunning(): Boolean = running
    }
}
