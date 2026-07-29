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
    val summaryText: String?,
    val textLines: List<String>,
)

sealed class NotificationProcessingResult {
    data class PaymentReceived(
        val amount: BigDecimal,
        val sender: String?,
        val normalizedText: String,
        val deduplicationKey: String,
    ) : NotificationProcessingResult()

    data class SpokenNotification(
        val spokenText: String,
        val normalizedText: String,
        val deduplicationKey: String,
        val appLabel: String,
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
    APP_NOT_SELECTED,
    PRIVACY_FILTERED,
}

data class PaymentRecord(
    val timestamp: Long,
    val amount: String,
    val sender: String? = null,
    val spokenText: String,
    val source: String,
    val announced: Boolean = true,
)

data class NativeSettings(
    val voiceEnabled: Boolean = true,
    val fullPhrase: Boolean = true,
    val continuousBackground: Boolean = false,
)

enum class AppReadMode {
    SMART_YAPE,
    TITLE_AND_CONTENT,
    TITLE_ONLY,
    SENDER_ONLY,
}

data class AppSelection(
    val packageName: String,
    val label: String,
    val enabled: Boolean,
    val readMode: AppReadMode,
    val detected: Boolean,
    val installed: Boolean = false,
    val userProfile: String = "user 0",
)

fun interface TimeProvider {
    fun currentTimeMillis(): Long
}
