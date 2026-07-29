package com.victormeneses.yape_notifier.storage

class ListenerDiagnosticsRepository(private val store: KeyValueStore) {
    fun update(values: Map<String, String?>) {
        values.forEach { (key, value) ->
            store.putString("$KEY_PREFIX$key", value.orEmpty())
        }
    }

    fun get(): Map<String, String> =
        keys.associateWith { store.getString("$KEY_PREFIX$it", "") }

    companion object {
        private const val KEY_PREFIX = "listener_diagnostic_"
        private val keys = listOf(
            "listenerConnected",
            "lastNotificationAt",
            "lastPackage",
            "lastDiscardReason",
            "lastAmount",
            "lastSender",
            "ttsState",
        )
    }
}
