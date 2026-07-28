package com.victormeneses.yape_notifier.notifications

class YapeNotificationProcessor(
    private val allowedPackages: Set<String>,
    private val deduplicator: NotificationDeduplicator,
) {
    fun process(payload: NotificationPayload): NotificationProcessingResult {
        if (!allowedPackages.contains(payload.packageName)) {
            return NotificationProcessingResult.Ignored(IgnoreReason.PACKAGE_NOT_ALLOWED)
        }
        val normalizedText = NotificationTextExtractor.fromPayload(payload)
        if (normalizedText.isBlank()) {
            return NotificationProcessingResult.Ignored(IgnoreReason.EMPTY_CONTENT)
        }
        if (YapeNotificationClassifier.hasNegativeExpression(normalizedText)) {
            return NotificationProcessingResult.Ignored(IgnoreReason.NEGATIVE_EXPRESSION)
        }
        if (!YapeNotificationClassifier.isReceivedPayment(normalizedText)) {
            return NotificationProcessingResult.Ignored(IgnoreReason.NOT_A_RECEIVED_PAYMENT)
        }
        val amount = YapeAmountParser.parse(normalizedText)
            ?: return NotificationProcessingResult.Ignored(IgnoreReason.AMOUNT_NOT_FOUND)
        val (isNew, dedupKey) = deduplicator.markIfNew(payload, amount, normalizedText)
        if (!isNew) {
            return NotificationProcessingResult.Ignored(IgnoreReason.DUPLICATE)
        }
        return NotificationProcessingResult.PaymentReceived(amount, normalizedText, dedupKey)
    }
}
