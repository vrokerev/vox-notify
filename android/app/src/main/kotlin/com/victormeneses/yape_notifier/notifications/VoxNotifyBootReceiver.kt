package com.victormeneses.yape_notifier.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.victormeneses.yape_notifier.BuildConfig
import com.victormeneses.yape_notifier.nativebridge.NotificationAccess
import com.victormeneses.yape_notifier.storage.ListenerDiagnosticsRepository
import com.victormeneses.yape_notifier.storage.NativeSettingsRepository
import com.victormeneses.yape_notifier.storage.SharedPreferencesStore

class VoxNotifyBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val appContext = context.applicationContext
        val store = SharedPreferencesStore(appContext)
        val diagnostics = ListenerDiagnosticsRepository(store)
        val settings = NativeSettingsRepository(store)
        diagnostics.update(
            mapOf(
                "lastProcessingResult" to "receiver:$action",
                "listenerRecoveryReason" to "receiver:$action",
            ),
        )

        val currentSettings = settings.get()

        // Restore the foreground service first, even if notification-listener access is missing.
        // The persistent notification is also the user's health indicator and must therefore be
        // restored after boot without requiring MainActivity to be opened.
        if (currentSettings.continuousBackground && currentSettings.voiceEnabled) {
            runCatching { VoxNotifyForegroundService.start(appContext) }
                .onSuccess {
                    diagnostics.update(
                        mapOf(
                            "lastProcessingResult" to "continuous_restore_requested:$action",
                            "persistentStatusRestore" to "foreground_requested:$action",
                        ),
                    )
                }
                .onFailure { error ->
                    diagnostics.update(
                        mapOf(
                            "lastProcessingResult" to "continuous_restore_failed:${error.javaClass.simpleName}",
                            "persistentStatusRestore" to "foreground_failed:${error.javaClass.simpleName}",
                        ),
                    )
                    // Best effort: leave a visible status notification even if the foreground
                    // service could not be restored on this boot.
                    VoxNotifyForegroundService.showCurrentStatus(appContext)
                }
        } else {
            // Reading is intentionally disabled: preserve the permanent status indicator rather
            // than making the notification disappear after a reboot.
            VoxNotifyForegroundService.showCurrentStatus(appContext)
            diagnostics.update(
                mapOf("persistentStatusRestore" to "disabled_status_posted:$action"),
            )
        }

        if (!NotificationAccess.isEnabled(appContext)) {
            diagnostics.update(
                mapOf(
                    "listenerRecoveryState" to "notification_access_disabled",
                    "lastProcessingResult" to "receiver_access_disabled:$action",
                ),
            )
            if (BuildConfig.DEBUG) Log.d(TAG, "receiver=$action notification access disabled")
            return
        }

        if (BuildConfig.DEBUG) Log.d(TAG, "receiver=$action requesting listener recovery")
        ListenerRebindScheduler(appContext, diagnostics).recover(
            reason = "receiver:$action",
            forceCycle = false,
        )
    }

    companion object {
        private const val TAG = "VoxNotify"
    }
}
