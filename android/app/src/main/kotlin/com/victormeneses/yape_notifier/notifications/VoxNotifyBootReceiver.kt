package com.victormeneses.yape_notifier.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.victormeneses.yape_notifier.BuildConfig
import com.victormeneses.yape_notifier.nativebridge.NotificationAccess
import com.victormeneses.yape_notifier.storage.ListenerDiagnosticsRepository
import com.victormeneses.yape_notifier.storage.SharedPreferencesStore

class VoxNotifyBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val appContext = context.applicationContext
        val diagnostics = ListenerDiagnosticsRepository(SharedPreferencesStore(appContext))
        diagnostics.update(mapOf("lastProcessingResult" to "receiver:$action"))
        if (!NotificationAccess.isEnabled(appContext)) {
            if (BuildConfig.DEBUG) Log.d(TAG, "receiver=$action notification access disabled")
            return
        }
        if (BuildConfig.DEBUG) Log.d(TAG, "receiver=$action requesting listener rebind")
        ListenerRebindScheduler(appContext, diagnostics).request("receiver:$action")
    }

    companion object {
        private const val TAG = "VoxNotify"
    }
}
