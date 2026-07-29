package com.victormeneses.yape_notifier.notifications

object YapeSenderParser {
    private val genericWords = setOf("yape", "bcp", "pago", "confirmacion", "confirmación")
    private val senderPatterns = listOf(
        Regex("""(?iu)(?:^|\s)(?:confirmaci[oó]n de pago\s*[-:]?\s*)?(?:yape!\s*)?(.+?)\s+te\s+envi[oó](?:\s+un\s+pago)?\s+por\s+S\s*/"""),
        Regex("""(?iu)(?:^|\s)(?:confirmaci[oó]n de pago\s*[-:]?\s*)?(?:yape!\s*)?(.+?)\s+te\s+yape[oó]\s+S\s*/"""),
        Regex("""(?iu)\brecibiste\s+S\s*/\s*\d{1,5}(?:[.,]\d{1,2})?\s+de\s+(.+?)(?:\s+por\s+Yape)?$"""),
        Regex("""(?iu)\bnuevo\s+yape\s+de\s+(.+?)\s+por\s+S\s*/"""),
    )

    fun parse(text: String): String? {
        val original = NotificationTextExtractor.normalize(text)
        for (pattern in senderPatterns) {
            val candidate = pattern.find(original)?.groupValues?.getOrNull(1)?.let(::clean)
            if (candidate != null) return candidate
        }
        return null
    }

    private fun clean(raw: String): String? {
        val withoutKnownPrefixes = raw
            .replace(Regex("""(?iu)^(?:yape!?\s*)+"""), "")
            .replace(Regex("""(?iu)^confirmaci[oó]n de pago\s*[-:]?\s*"""), "")
            .replace(Regex("""(?iu)\b(?:te\s+envi[oó](?:\s+un\s+pago)?|te\s+yape[oó]|por\s+S\s*/.*)$"""), "")
            .trim(' ', '.', ',', ':', '-', '!', '¡')
            .replace(Regex("\\s+"), " ")
        if (withoutKnownPrefixes.isBlank()) return null
        val searchable = YapeNotificationClassifier.searchable(withoutKnownPrefixes)
        if (genericWords.any { searchable == it }) return null
        if (searchable.contains("enviaste") || searchable.contains("pago realizado")) return null
        return withoutKnownPrefixes
    }
}
