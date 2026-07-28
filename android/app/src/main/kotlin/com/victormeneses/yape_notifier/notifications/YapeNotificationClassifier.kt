package com.victormeneses.yape_notifier.notifications

import java.text.Normalizer
import java.util.Locale

object YapeNotificationClassifier {
    private val negative = listOf(
        "enviaste",
        "pagaste",
        "transferiste",
        "operacion rechazada",
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
    )

    fun hasNegativeExpression(text: String): Boolean {
        val normalized = searchable(text)
        return negative.any { normalized.contains(it) }
    }

    fun isReceivedPayment(text: String): Boolean {
        val normalized = searchable(text)
        return received.any { normalized.contains(it) }
    }

    fun searchable(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase(Locale.forLanguageTag("es-PE"))
}
