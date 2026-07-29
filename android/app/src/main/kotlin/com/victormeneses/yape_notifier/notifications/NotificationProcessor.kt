package com.victormeneses.yape_notifier.notifications

class NotificationProcessor(
    private val appSelections: Map<String, AppSelection>,
    private val deduplicator: NotificationDeduplicator,
) {
    private val yapeProcessor = YapeNotificationProcessor(AllowedPackages.current(), deduplicator)

    fun process(payload: NotificationPayload): NotificationProcessingResult {
        val app = appSelections[payload.packageName]
            ?: return NotificationProcessingResult.Ignored(IgnoreReason.APP_NOT_SELECTED)
        if (!app.enabled) {
            return NotificationProcessingResult.Ignored(IgnoreReason.APP_NOT_SELECTED)
        }

        val normalizedText = NotificationTextExtractor.fromPayload(payload)
        if (normalizedText.isBlank()) {
            return NotificationProcessingResult.Ignored(IgnoreReason.EMPTY_CONTENT)
        }

        if (app.readMode == AppReadMode.SMART_YAPE) {
            return yapeProcessor.process(payload)
        }

        if (PrivacyFilter.shouldBlock(normalizedText)) {
            return NotificationProcessingResult.Ignored(IgnoreReason.PRIVACY_FILTERED)
        }

        val spokenText = GenericNotificationFormatter.format(payload, app)
        if (spokenText.isBlank()) {
            return NotificationProcessingResult.Ignored(IgnoreReason.EMPTY_CONTENT)
        }
        val (isNew, dedupKey) = deduplicator.markTextIfNew(payload, normalizedText)
        if (!isNew) {
            return NotificationProcessingResult.Ignored(IgnoreReason.DUPLICATE)
        }
        return NotificationProcessingResult.SpokenNotification(
            spokenText = spokenText,
            normalizedText = normalizedText,
            deduplicationKey = dedupKey,
            appLabel = app.label,
        )
    }
}
