package com.victormeneses.yape_notifier.notifications

object GenericNotificationFormatter {
    fun format(payload: NotificationPayload, app: AppSelection): String {
        val title = NotificationTextExtractor.normalize(payload.title.orEmpty())
        val body = NotificationTextExtractor.normalize(
            listOfNotNull(payload.text, payload.bigText)
                .plus(payload.textLines)
                .joinToString(" "),
        )
        val content = when (app.readMode) {
            AppReadMode.TITLE_ONLY,
            AppReadMode.SENDER_ONLY,
            -> title
            AppReadMode.TITLE_AND_CONTENT -> listOf(title, body)
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString(": ")
            AppReadMode.SMART_YAPE -> NotificationTextExtractor.fromPayload(payload)
        }
        return listOf(app.label, content)
            .filter { it.isNotBlank() }
            .joinToString(". ")
            .trim()
    }
}
