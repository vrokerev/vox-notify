package com.victormeneses.yape_notifier.notifications

object PrivacyFilter {
    private val sensitive = Regex(
        "(?i)\\b(c[oó]digo de verificaci[oó]n|otp|contrase[nñ]a|clave|token|c[oó]digo de seguridad)\\b",
    )

    fun shouldBlock(text: String): Boolean = sensitive.containsMatchIn(text)
}
