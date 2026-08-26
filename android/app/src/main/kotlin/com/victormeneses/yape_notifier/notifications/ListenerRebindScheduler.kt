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
            diagnosticsRepository.update(
                mapOf(
                    "listenerRecoveryState" to "max_attempts_reached",
                    "listenerRecoveryReason" to reason,
                ),
            )
            if (BuildConfig.DEBUG) Log.d(TAG, "rebind max attempts reached reason=$reason")
            return
        }
        val delay = delays[attempts.coerceAtMost(delays.lastIndex)]
        attempts += 1
        diagnosticsRepository.update(
            mapOf(
                "listenerRecoveryState" to "scheduled",
                "listenerRecoveryReason" to reason,
                "listenerRecoveryAttempt" to attempts.toString(),
            ),
        )
        if (BuildConfig.DEBUG) Log.d(TAG, "rebind scheduled attempt=$attempts delay=$delay reason=$reason")
        handler.postDelayed({ request("scheduled:$reason") }, delay)
    }

    fun request(reason: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val now = System.currentTimeMillis()
        diagnosticsRepository.update(
            mapOf(
                "rebindRequestedAt" to now.toString(),
                "listenerRecoveryState" to "rebind_requested",
                "listenerRecoveryReason" to reason,
            ),
        )
        val component = component()
        if (BuildConfig.DEBUG) Log.d(TAG, "requestRebind reason=$reason component=$component")
        runCatching { NotificationListenerService.requestRebind(component) }
            .onFailure { error ->
                diagnosticsRepository.update(
                    mapOf(
                        "listenerRecoveryState" to "rebind_failed:${error.javaClass.simpleName}",
                        "listenerRecoveryReason" to reason,
                    ),
                )
            }
        VoxNotifyEventBus.emit("listener_rebind_requested")
    }

    /**
     * Performs a stronger recovery when persisted diagnostics say the listener was connected,
     * but its health heartbeat stopped. Android 14+ lets us explicitly unbind our own listener
     * and then request a clean bind again. Older Android versions fall back to requestRebind().
     */
    fun recover(reason: String, forceCycle: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val component = component()
        val now = System.currentTimeMillis()
        diagnosticsRepository.update(
            mapOf(
                "listenerRecoveryAt" to now.toString(),
                "listenerRecoveryReason" to reason,
                "listenerRecoveryState" to if (forceCycle) "cycle_requested" else "rebind_requested",
            ),
        )

        if (forceCycle && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (BuildConfig.DEBUG) Log.d(TAG, "requestUnbind before recovery reason=$reason component=$component")
            runCatching { NotificationListenerService.requestUnbind(component) }
                .onSuccess {
                    handler.postDelayed({ request("recovery_cycle:$reason") }, RECOVERY_REBIND_DELAY_MS)
                }
                .onFailure { error ->
                    diagnosticsRepository.update(
                        mapOf(
                            "listenerRecoveryState" to "unbind_failed:${error.javaClass.simpleName}",
                            "listenerRecoveryReason" to reason,
                        ),
                    )
                    request("recovery_fallback:$reason")
                }
            return
        }

        request("recovery:$reason")
    }

    private fun component(): ComponentName =
        ComponentName(context.applicationContext, YapeNotificationListenerService::class.java)

    companion object {
        private const val TAG = "VoxNotify"
        private const val MAX_ATTEMPTS = 5
        private const val RECOVERY_REBIND_DELAY_MS = 1_500L
        private val delays = longArrayOf(1_000, 5_000, 15_000, 60_000, 180_000)
    }
}
