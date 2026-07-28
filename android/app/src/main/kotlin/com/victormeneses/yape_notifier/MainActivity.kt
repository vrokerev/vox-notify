package com.victormeneses.yape_notifier

import com.victormeneses.yape_notifier.nativebridge.NativeChannelHandler
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            NativeChannelHandler.CHANNEL_NAME,
        ).setMethodCallHandler(NativeChannelHandler(this))
    }
}
