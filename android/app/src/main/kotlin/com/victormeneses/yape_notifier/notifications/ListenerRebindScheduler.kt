package com.victormeneses.yape_notifier.notifications

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log
import com.victormeneses.yape_notifier.BuildConfig
import com.victormeneses.yape_notifier.storage.ListenerDiagnosticsRepository
import com.victormeneses.yape_notifier.storage.VoxNotifyEventBus

class ListenerRebindScheduler(
    private val context: Context,
    private val diagnosticsRepository: ListenerDiagnosticsRepository,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var attempts = 0

    fun cancel() {
        attempts = 0
        handler.removeCallbacksAndMessages(null)
    }

    fun schedule(reason: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        if (attempts >= MAX_ATTEMPTS) {
            if (BuildConfig.DEBUG) Log.d(TAG, "rebind max attempts reached reason=$reason")
            return
        }
        val delay = delays[attempts.coerceAtMost(delays.lastIndex)]
        attempts += 1
        if (BuildConfig.DEBUG) Log.d(TAG, "rebind scheduled attempt=$attempts delay=$delay reason=$reason")
        handler.postDelayed({ request("scheduled:$reason") }, delay)
    }

    fun request(reason: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val now = System.currentTimeMillis()
        diagnosticsRepository.update(mapOf("rebindRequestedAt" to now.toString()))
        val component = ComponentName(context.applicationContext, YapeNotificationListenerService::class.java)
        if (BuildConfig.DEBUG) Log.d(TAG, "requestRebind reason=$reason component=$component")
        NotificationListenerService.requestRebind(component)
        VoxNotifyEventBus.emit("listener_rebind_requested")
    }

    companion object {
        private const val TAG = "VoxNotify"
        private const val MAX_ATTEMPTS = 5
        private val delays = longArrayOf(1_000, 5_000, 15_000, 60_000, 180_000)
    }
}
