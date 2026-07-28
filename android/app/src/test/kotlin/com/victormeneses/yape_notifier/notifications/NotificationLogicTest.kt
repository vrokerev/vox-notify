package com.victormeneses.yape_notifier.notifications

import com.victormeneses.yape_notifier.storage.InMemoryKeyValueStore
import com.victormeneses.yape_notifier.storage.NativeSettingsRepository
import com.victormeneses.yape_notifier.storage.PaymentHistoryRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

class NotificationLogicTest {
    @Test
    fun parserAcceptsRequiredAmounts() {
        mapOf(
            "Recibiste un Yape de S/ 20" to "20.00",
            "Te yapearon S/20.50" to "20.50",
            "Has recibido S/. 1,20" to "1.20",
            "Nuevo Yape: 100 soles" to "100.00",
            "Yape recibido por S/ 0.50" to "0.50",
            "Recibiste 1 sol" to "1.00",
            "Recibiste S/ 1.01" to "1.01",
        ).forEach { (text, expected) ->
            assertEquals(expected, YapeAmountParser.parse(text)?.toPlainString())
        }
    }

    @Test
    fun parserRejectsNonAmountsAndInvalidAmounts() {
        listOf(
            "Tu código es 123456",
            "Recibiste dinero a las 10:30",
            "Recibiste un mensaje a las 08:45",
            "Tu número termina en 2020",
            "Recibiste S/ 0",
            "Recibiste S/ 100000",
        ).forEach { text ->
            assertEquals(null, YapeAmountParser.parse(text))
        }
    }

    @Test
    fun classifierHandlesNegativeAndReceivedExpressions() {
        listOf(
            "Enviaste S/ 30",
            "Pagaste S/ 15",
            "Transferiste S/ 50",
            "Promoción: gana S/ 100",
            "Oferta de hasta S/ 200",
            "Operación rechazada por S/ 25",
            "No se pudo completar el pago de S/ 10",
        ).forEach { assertTrue(YapeNotificationClassifier.hasNegativeExpression(it)) }

        listOf(
            "recibiste un yape de s/ 20",
            "TE YAPEARON S/20.50",
            "Has recibido S/. 1,20",
            "Nuevo Yape: 100 soles",
            "Yape recibido S/ 2",
        ).forEach { assertTrue(YapeNotificationClassifier.isReceivedPayment(it)) }
    }

    @Test
    fun extractorCombinesNullableFieldsLinesSpacesAndInvisibleCharacters() {
        val payload = payload(
            title = "",
            text = "Recibiste\u200B   un",
            bigText = "Yape de",
            textLines = listOf("S/ 20"),
        )
        assertEquals("Recibiste un Yape de S/ 20", NotificationTextExtractor.fromPayload(payload))
    }

    @Test
    fun processorAcceptsOnlyValidReceivedPayments() {
        val processor = processor()
        val accepted = processor.process(payload(text = "Recibiste un Yape de S/ 20", key = "a"))
        assertTrue(accepted is NotificationProcessingResult.PaymentReceived)

        mapOf(
            "Enviaste S/ 30" to IgnoreReason.NEGATIVE_EXPRESSION,
            "Pagaste S/ 15" to IgnoreReason.NEGATIVE_EXPRESSION,
            "Promoción: gana S/ 100" to IgnoreReason.NEGATIVE_EXPRESSION,
            "Operación rechazada por S/ 25" to IgnoreReason.NEGATIVE_EXPRESSION,
            "Tu código es 123456" to IgnoreReason.NOT_A_RECEIVED_PAYMENT,
            "Recibiste dinero a las 10:30" to IgnoreReason.AMOUNT_NOT_FOUND,
        ).forEach { (text, reason) ->
            val result = processor.process(payload(text = text, key = text))
            assertEquals(NotificationProcessingResult.Ignored(reason), result)
        }
    }

    @Test
    fun processorFindsAmountInBigTextAndTextLines() {
        assertTrue(processor().process(payload(text = null, bigText = "Recibiste un Yape de S/ 12", key = "big")) is NotificationProcessingResult.PaymentReceived)
        assertTrue(processor().process(payload(text = "Nuevo Yape", textLines = listOf("Cliente", "S/ 8.90"), key = "lines")) is NotificationProcessingResult.PaymentReceived)
    }

    @Test
    fun packageAllowlistIncludesTestSenderOnlyWhenProvided() {
        val releaseProcessor = YapeNotificationProcessor(
            setOf(AllowedPackages.YAPE_PACKAGE),
            NotificationDeduplicator(TimeProvider { 1L }),
        )
        val debugProcessor = YapeNotificationProcessor(
            setOf(AllowedPackages.YAPE_PACKAGE, AllowedPackages.TEST_SENDER_PACKAGE),
            NotificationDeduplicator(TimeProvider { 1L }),
        )

        assertEquals(
            NotificationProcessingResult.Ignored(IgnoreReason.PACKAGE_NOT_ALLOWED),
            releaseProcessor.process(payload(packageName = AllowedPackages.TEST_SENDER_PACKAGE, text = "Recibiste S/ 2")),
        )
        assertTrue(debugProcessor.process(payload(packageName = AllowedPackages.TEST_SENDER_PACKAGE, text = "Recibiste S/ 2")) is NotificationProcessingResult.PaymentReceived)
    }

    @Test
    fun deduplicatorIsKeyAwareAndExpiresEntries() {
        val clock = ManualClock(1_000)
        val deduplicator = NotificationDeduplicator(TimeProvider { clock.now }, windowMillis = 500)
        val first = payload(text = "Recibiste S/ 20", key = "same")
        val amount = BigDecimal("20.00")

        assertTrue(deduplicator.markIfNew(first, amount, "Recibiste S/ 20").first)
        assertFalse(deduplicator.markIfNew(first, amount, "Recibiste S/ 20").first)
        assertTrue(deduplicator.markIfNew(payload(text = "Recibiste S/ 20", key = "other"), amount, "Recibiste S/ 20").first)

        clock.now = 1_700
        assertTrue(deduplicator.markIfNew(first, amount, "Recibiste S/ 20").first)
        deduplicator.prune()
        assertTrue(deduplicator.size() <= 1)
    }

    @Test
    fun speechFormatterHandlesSingularAndPlural() {
        assertEquals("Yape recibido. 1 sol.", YapeSpeechFormatter.phrase(BigDecimal("1.00"), true))
        assertEquals("2 soles.", YapeSpeechFormatter.phrase(BigDecimal("2.00"), true).removePrefix("Yape recibido. "))
        assertEquals("1 sol con 1 céntimo", YapeSpeechFormatter.phrase(BigDecimal("1.01"), false))
        assertEquals("25 soles con 50 céntimos", YapeSpeechFormatter.phrase(BigDecimal("25.50"), false))
    }

    @Test
    fun historyPersistsMostRecentHundredAndClears() {
        val repo = PaymentHistoryRepository(InMemoryKeyValueStore())
        repeat(105) {
            repo.add(PaymentRecord(it.toLong(), "$it.00", "Yape recibido. $it soles.", "test"))
        }
        assertEquals(100, repo.get().size)
        assertEquals("104.00", repo.get().first().amount)
        repo.clear()
        assertTrue(repo.get().isEmpty())
    }

    @Test
    fun nativeSettingsRoundTrip() {
        val repo = NativeSettingsRepository(InMemoryKeyValueStore())
        assertTrue(repo.get().voiceEnabled)
        repo.update(NativeSettings(voiceEnabled = false, fullPhrase = false))
        assertFalse(repo.get().voiceEnabled)
        assertFalse(repo.get().fullPhrase)
    }

    @Test
    fun paymentReceivedResultContainsAmountTextAndDeduplicationKey() {
        val result = processor().process(payload(text = "Recibiste un Yape de S/ 25,50", key = "x"))
        assertTrue(result is NotificationProcessingResult.PaymentReceived)
        result as NotificationProcessingResult.PaymentReceived
        assertEquals("25.50", result.amount.toPlainString())
        assertNotNull(result.deduplicationKey)
        assertTrue(result.normalizedText.contains("Recibiste"))
    }

    private fun processor(): YapeNotificationProcessor =
        YapeNotificationProcessor(
            setOf(AllowedPackages.YAPE_PACKAGE),
            NotificationDeduplicator(TimeProvider { 1_000L }),
        )

    private fun payload(
        packageName: String = AllowedPackages.YAPE_PACKAGE,
        key: String = "key",
        text: String? = null,
        title: String? = "Yape",
        bigText: String? = null,
        textLines: List<String> = emptyList(),
    ): NotificationPayload =
        NotificationPayload(
            packageName = packageName,
            notificationKey = key,
            notificationId = 1,
            postTime = 1L,
            title = title,
            text = text,
            bigText = bigText,
            subText = null,
            infoText = null,
            textLines = textLines,
        )

    private data class ManualClock(var now: Long)
}
