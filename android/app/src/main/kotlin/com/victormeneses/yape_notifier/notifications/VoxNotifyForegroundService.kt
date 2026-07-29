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
import com.victormeneses.yape_notifier.storage.SharedPreferencesStore
import com.victormeneses.yape_notifier.storage.VoxNotifyEventBus

class VoxNotifyForegroundService : Service() {
    private lateinit var diagnosticsRepository: ListenerDiagnosticsRepository

    override fun onCreate() {
        super.onCreate()
        diagnosticsRepository = ListenerDiagnosticsRepository(SharedPreferencesStore(applicationContext))
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification())
        diagnosticsRepository.update(mapOf("foregroundSpeechServiceRunning" to "true"))
        VoxNotifyEventBus.emit("foreground_service_changed")
        ListenerRebindScheduler(applicationContext, diagnosticsRepository).request("foreground_service_start")
        return START_STICKY
    }

    override fun onDestroy() {
        diagnosticsRepository.update(mapOf("foregroundSpeechServiceRunning" to "false"))
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
    }
}
