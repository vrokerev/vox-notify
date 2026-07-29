package com.victormeneses.yape_notifier.storage

class ListenerDiagnosticsRepository(private val store: KeyValueStore) {
    fun update(values: Map<String, String?>) {
        values.forEach { (key, value) ->
            store.putString("$KEY_PREFIX$key", value.orEmpty())
        }
        VoxNotifyEventBus.emit("diagnostics_changed")
    }

    fun get(): Map<String, String> =
        keys.associateWith { store.getString("$KEY_PREFIX$it", "") }

    companion object {
        private const val KEY_PREFIX = "listener_diagnostic_"
        private val keys = listOf(
            "listenerConnected",
            "processCreatedAt",
            "listenerConnectedAt",
            "listenerDisconnectedAt",
            "rebindRequestedAt",
            "lastCallbackAt",
            "lastCallbackPackage",
            "lastProcessingResult",
            "lastTtsQueuedAt",
            "lastTtsStartedAt",
            "lastTtsCompletedAt",
            "lastTtsError",
            "lastAudioFocusResult",
            "lastTtsFocusRetryAt",
            "lastSpokenText",
            "foregroundSpeechServiceRunning",
            "foregroundServiceStartedAt",
            "foregroundServiceStoppedAt",
            "foregroundHeartbeatAt",
            "batteryOptimizationIgnored",
            "manufacturer",
            "appStandbyBucket",
            "lastExitReason",
            "lastNotificationAt",
            "lastPackage",
            "lastDiscardReason",
            "lastAmount",
            "lastSender",
            "ttsState",
            "lastYapePackageName",
            "lastYapePostTime",
            "lastYapeTitle",
            "lastYapeText",
            "lastYapeBigText",
            "lastYapeSubText",
            "lastYapeSummaryText",
            "lastYapeTextLines",
            "lastYapeParserResult",
        )
    }
}
