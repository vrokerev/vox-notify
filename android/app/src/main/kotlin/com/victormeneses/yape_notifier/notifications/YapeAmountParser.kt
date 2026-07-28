package com.victormeneses.yape_notifier.notifications

import java.math.BigDecimal
import java.math.RoundingMode

object YapeAmountParser {
    private val currencyPattern = Regex("""(?i)(?:S\s*/\.?\s*)(\d{1,5})(?:[.,](\d{1,2}))?""")
    private val solesPattern = Regex("""(?i)\b(\d{1,5})(?:[.,](\d{1,2}))?\s+sol(?:es)?\b""")
    private val maxAmount = BigDecimal("9999.99")

    fun parse(text: String): BigDecimal? {
        val match = currencyPattern.find(text) ?: solesPattern.find(text) ?: return null
        val whole = match.groupValues[1]
        val cents = match.groupValues.getOrNull(2).orEmpty()
        if (whole.length > 1 && whole.startsWith("0")) return null
        val normalized = if (cents.isBlank()) whole else "$whole.${cents.padEnd(2, '0')}"
        val amount = normalized.toBigDecimalOrNull()?.setScale(2, RoundingMode.UNNECESSARY) ?: return null
        if (amount <= BigDecimal.ZERO || amount > maxAmount) return null
        return amount
    }
}
