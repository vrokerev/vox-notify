package com.victormeneses.yape_notifier.notifications

import java.text.Normalizer
import java.util.Locale

object YapeNotificationClassifier {
    private val negative = listOf(
        "enviaste",
        "enviaste un pago",
        "pago realizado",
        "pagaste",
        "transferiste",
        "operacion rechazada",
        "rechazada",
        "promocion",
        "descuento",
        "oferta",
        "no se pudo completar",
    )
    private val received = listOf(
        "recibiste",
        "recibiste un yape",
        "te yapearon",
        "yape recibido",
        "nuevo yape",
        "has recibido",
        "te envio un pago",
        "te envio",
        "te yapeo",
        "confirmacion de pago",
    )

    fun hasNegativeExpression(text: String): Boolean {
        val normalized = searchable(text)
        return negative.any { normalized.contains(it) }
    }

    fun isReceivedPayment(text: String): Boolean {
        val normalized = searchable(text)
        val hasReceivedPhrase = received.any { normalized.contains(it) }
        val hasReceivedStructure = Regex("""\bte\s+(envio|yapeo)\b""").containsMatchIn(normalized) ||
            Regex("""\brecibiste\b.+\bde\b""").containsMatchIn(normalized) ||
            Regex("""\bnuevo yape\s+de\b""").containsMatchIn(normalized)
        val titleOnlyConfirmation = normalized.trim() == "confirmacion de pago"
        return (hasReceivedPhrase || hasReceivedStructure) && !titleOnlyConfirmation
    }

    fun searchable(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.forLanguageTag("es-PE"))
}
