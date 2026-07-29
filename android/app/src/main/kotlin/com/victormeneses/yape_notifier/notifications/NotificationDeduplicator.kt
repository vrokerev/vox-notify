package com.victormeneses.yape_notifier.notifications

import com.victormeneses.yape_notifier.storage.KeyValueStore
import java.math.BigDecimal

class NotificationDeduplicator(
    private val timeProvider: TimeProvider,
    private val windowMillis: Long = 90_000,
    private val store: KeyValueStore? = null,
    private val maxItems: Int = 80,
) {
    private val seen = linkedMapOf<String, Long>()

    fun markIfNew(payload: NotificationPayload, amount: BigDecimal, normalizedText: String, sender: String? = null): Pair<Boolean, String> {
        val now = timeProvider.currentTimeMillis()
        prune(now)
        val key = "${payload.packageName}|${payload.notificationKey}|${payload.notificationId}|${amount.toPlainString()}|${sender.orEmpty()}|${normalizedText.hashCode()}"
        if (seen.containsKey(key)) return false to key
        seen[key] = now
        persist()
        return true to key
    }

    fun markTextIfNew(payload: NotificationPayload, normalizedText: String): Pair<Boolean, String> {
        val now = timeProvider.currentTimeMillis()
        prune(now)
        val key = "${payload.packageName}|${payload.notificationKey}|${payload.notificationId}|${normalizedText.hashCode()}"
        if (seen.containsKey(key)) return false to key
        seen[key] = now
        persist()
        return true to key
    }

    fun prune(now: Long = timeProvider.currentTimeMillis()) {
        load()
        val expired = seen.filterValues { now - it > windowMillis }.keys
        expired.forEach { seen.remove(it) }
        while (seen.size > maxItems) {
            val first = seen.keys.firstOrNull() ?: break
            seen.remove(first)
        }
        persist()
    }

    fun size(): Int = seen.size

    private fun load() {
        val saved = store?.getString(KEY_DEDUP, "") ?: return
        if (saved.isBlank() || seen.isNotEmpty()) return
        saved.lineSequence()
            .mapNotNull { line ->
                val separator = line.lastIndexOf('\t')
                if (separator <= 0) return@mapNotNull null
                val key = line.substring(0, separator)
                val timestamp = line.substring(separator + 1).toLongOrNull() ?: return@mapNotNull null
                key to timestamp
            }
            .forEach { (key, timestamp) -> seen[key] = timestamp }
    }

    private fun persist() {
        store?.putString(
            KEY_DEDUP,
            seen.entries.joinToString("\n") { "${it.key}\t${it.value}" },
        )
    }

    companion object {
        private const val KEY_DEDUP = "notification_deduplication_keys"
    }
}
