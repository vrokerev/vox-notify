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
            addAll(payload.textLines)
        }
        return normalize(pieces.filterNotNull().joinToString(" "))
    }

    fun fromNotification(packageName: String, key: String, id: Int, postTime: Long, notification: Notification): NotificationPayload {
        val extras = notification.extras
        val lines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.mapNotNull { it?.toString() }
            ?: emptyList()
        return NotificationPayload(
            packageName = packageName,
            notificationKey = key,
            notificationId = id,
            postTime = postTime,
            title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString(),
            textLines = lines,
        )
    }

    fun normalize(raw: String): String =
        raw.replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
}
