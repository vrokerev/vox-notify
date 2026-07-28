package com.victormeneses.yape_notifier.nativebridge

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.victormeneses.yape_notifier.notifications.YapeNotificationListenerService

object NotificationAccess {
    fun isEnabled(context: Context): Boolean {
        val expected = ComponentName(context, YapeNotificationListenerService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
        return enabled.split(":").any { it.equals(expected, ignoreCase = true) }
    }
}
