package com.victormeneses.yape_notifier.test_sender

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class TestNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        NotificationPublisher.publish(
            context = context,
            title = intent.getStringExtra("title") ?: "Confirmación de Pago",
            text = intent.getStringExtra("text")
                ?: "Yape! VICTOR MANUEL MENESES te envió un pago por S/ 1",
            id = intent.getIntExtra("id", System.currentTimeMillis().toInt()),
        )
    }
}
