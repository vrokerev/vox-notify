package com.victormeneses.yape_notifier.notifications

import java.math.BigDecimal

class NotificationDeduplicator(
    private val timeProvider: TimeProvider,
    private val windowMillis: Long = 90_000,
) {
    private val seen = linkedMapOf<String, Long>()

    fun markIfNew(payload: NotificationPayload, amount: BigDecimal, normalizedText: String, sender: String? = null): Pair<Boolean, String> {
        val now = timeProvider.currentTimeMillis()
        prune(now)
        val key = "${payload.packageName}|${payload.notificationKey}|${payload.notificationId}|${amount.toPlainString()}|${sender.orEmpty()}|${normalizedText.hashCode()}"
        if (seen.containsKey(key)) return false to key
        seen[key] = now
        return true to key
    }

    fun markTextIfNew(payload: NotificationPayload, normalizedText: String): Pair<Boolean, String> {
        val now = timeProvider.currentTimeMillis()
        prune(now)
        val key = "${payload.packageName}|${payload.notificationKey}|${payload.notificationId}|${normalizedText.hashCode()}"
        if (seen.containsKey(key)) return false to key
        seen[key] = now
        return true to key
    }

    fun prune(now: Long = timeProvider.currentTimeMillis()) {
        val expired = seen.filterValues { now - it > windowMillis }.keys
        expired.forEach { seen.remove(it) }
    }

    fun size(): Int = seen.size
}
