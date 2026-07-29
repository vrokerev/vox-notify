package com.victormeneses.yape_notifier.notifications

import android.app.Notification

object NotificationTextExtractor {
    fun fromPayload(payload: NotificationPayload): String {
        val pieces = buildList {
            add(payload.title)
            add(payload.text)
            add(payload.bigText)
            add(payload.subText)
            add(payload.infoText)
            add(payload.summaryText)
            addAll(payload.textLines)
        }
        return combine(pieces.filterNotNull())
    }

    fun originalFromPayload(payload: NotificationPayload): String {
        val pieces = buildList {
            add(payload.title)
            add(payload.text)
            add(payload.bigText)
            add(payload.subText)
            add(payload.infoText)
            add(payload.summaryText)
            addAll(payload.textLines)
        }
        return combine(pieces.filterNotNull())
    }

    fun fromNotification(packageName: String, key: String, id: Int, postTime: Long, notification: Notification): NotificationPayload {
        val extras = notification.extras
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString() }
            ?: emptyList()
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()
        val summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()
        return NotificationPayload(
            packageName = packageName,
            notificationKey = key,
            notificationId = id,
            postTime = postTime,
            title = title,
            text = text,
            bigText = bigText,
            subText = subText,
            infoText = infoText,
            summaryText = summaryText,
            textLines = lines.distinct(),
        )
    }

    private fun combine(pieces: List<String>): String =
        normalize(pieces.map { normalize(it) }.filter { it.isNotBlank() }.distinct().joinToString(" "))

    fun normalize(raw: String): String =
        raw.replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
}
