package com.victormeneses.yape_notifier.notifications

import java.math.BigDecimal
import java.math.RoundingMode

object YapeSpeechFormatter {
    fun phrase(amount: BigDecimal, sender: String?, includeSender: Boolean): String {
        val amountText = amountText(amount)
        if (!includeSender) return "$amountText."
        val cleanSender = sender?.takeIf { it.isNotBlank() }
        return if (cleanSender != null) {
            "$cleanSender te envió $amountText por Yape."
        } else {
            "Recibiste $amountText por Yape."
        }
    }

    fun amountText(amount: BigDecimal): String {
        val scaled = amount.setScale(2, RoundingMode.HALF_UP)
        val soles = scaled.toBigInteger().toInt()
        val cents = scaled.remainder(BigDecimal.ONE).movePointRight(2).toInt()
        val solText = "$soles ${if (soles == 1) "sol" else "soles"}"
        if (cents == 0) return solText
        val centText = "$cents ${if (cents == 1) "céntimo" else "céntimos"}"
        return "$solText con $centText"
    }
}
