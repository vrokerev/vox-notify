package com.victormeneses.yape_notifier.notifications

import java.math.BigDecimal

data class NotificationPayload(
    val packageName: String,
    val notificationKey: String,
    val notificationId: Int,
    val postTime: Long,
    val title: String?,
    val text: String?,
    val bigText: String?,
    val subText: String?,
    val infoText: String?,
    val textLines: List<String>,
)

sealed class NotificationProcessingResult {
    data class PaymentReceived(
        val amount: BigDecimal,
        val normalizedText: String,
        val deduplicationKey: String,
    ) : NotificationProcessingResult()

    data class Ignored(val reason: IgnoreReason) : NotificationProcessingResult()
}

enum class IgnoreReason {
    PACKAGE_NOT_ALLOWED,
    EMPTY_CONTENT,
    NEGATIVE_EXPRESSION,
    NOT_A_RECEIVED_PAYMENT,
    AMOUNT_NOT_FOUND,
    INVALID_AMOUNT,
    DUPLICATE,
}

data class PaymentRecord(
    val timestamp: Long,
    val amount: String,
    val spokenText: String,
    val source: String,
)

data class NativeSettings(
    val voiceEnabled: Boolean = true,
    val fullPhrase: Boolean = true,
)

fun interface TimeProvider {
    fun currentTimeMillis(): Long
}
