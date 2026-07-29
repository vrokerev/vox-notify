package com.victormeneses.yape_notifier

import com.victormeneses.yape_notifier.nativebridge.NativeChannelHandler
import com.victormeneses.yape_notifier.storage.VoxNotifyEventBus
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            NativeChannelHandler.CHANNEL_NAME,
        ).setMethodCallHandler(NativeChannelHandler(this))
        EventChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            NativeChannelHandler.EVENT_CHANNEL_NAME,
        ).setStreamHandler(
            object : EventChannel.StreamHandler {
                override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                    VoxNotifyEventBus.attach(events)
                }

                override fun onCancel(arguments: Any?) {
                    VoxNotifyEventBus.detach()
                }
            },
        )
    }
}
