package com.victormeneses.yape_notifier.storage

import android.os.Handler
import android.os.Looper
import io.flutter.plugin.common.EventChannel

object VoxNotifyEventBus {
    private val mainHandler: Handler? = runCatching { Handler(Looper.getMainLooper()) }.getOrNull()
    private var sink: EventChannel.EventSink? = null

    fun attach(events: EventChannel.EventSink?) {
        sink = events
    }

    fun detach() {
        sink = null
    }

    fun emit(type: String) {
        val payload = mapOf(
            "type" to type,
            "timestamp" to System.currentTimeMillis(),
        )
        val handler = mainHandler
        if (handler == null) {
            sink?.success(payload)
        } else {
            handler.post {
                sink?.success(payload)
            }
        }
    }
}
