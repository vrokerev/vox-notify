package com.victormeneses.yape_notifier.notifications

import com.victormeneses.yape_notifier.storage.InMemoryKeyValueStore
import com.victormeneses.yape_notifier.storage.AppSelectionRepository
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
            "Confirmación de Pago Yape! VICTOR MANUEL MENESES te envió un pago por S/ 1",
            "Carlos te yapeó S/ 15",
        ).forEach { assertTrue(YapeNotificationClassifier.isReceivedPayment(it)) }
    }

    @Test
    fun senderParserExtractsRealYapeFormats() {
        mapOf(
            "Yape! VICTOR MANUEL MENESES te envió un pago por S/ 1" to "VICTOR MANUEL MENESES",
            "Yape! María López te envió un pago por S/ 25.50" to "María López",
            "Yape! José Pérez te envió un pago por S/ 1,20" to "José Pérez",
            "Confirmación de Pago - ANA MARÍA RAMOS te envió un pago por S/ 100" to "ANA MARÍA RAMOS",
            "Carlos te envió un pago por S/1" to "Carlos",
            "Recibiste S/ 20 de Carlos Ramírez" to "Carlos Ramírez",
            "Carlos te yapeó S/ 15" to "Carlos",
            "Nuevo Yape de Ana Torres por S/ 10" to "Ana Torres",
        ).forEach { (text, expected) ->
            assertEquals(expected, YapeSenderParser.parse(text))
        }
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
            "Tu código es 123456" to IgnoreReason.AMOUNT_NOT_FOUND,
            "Recibiste dinero a las 10:30" to IgnoreReason.AMOUNT_NOT_FOUND,
        ).forEach { (text, reason) ->
            val result = processor.process(payload(text = text, key = text))
            assertEquals(NotificationProcessingResult.Ignored(reason), result)
        }
    }

    @Test
    fun processorAcceptsRealYapeNotificationWithSender() {
        val result = processor().process(
            payload(
                key = "real-yape-1",
                title = "Confirmación de Pago",
                text = "Yape! VICTOR MANUEL MENESES te envió un pago por S/ 1",
            ),
        )
        assertTrue(result is NotificationProcessingResult.PaymentReceived)
        result as NotificationProcessingResult.PaymentReceived
        assertEquals(BigDecimal("1.00"), result.amount)
        assertEquals("VICTOR MANUEL MENESES", result.sender)
        assertEquals(
            "VICTOR MANUEL MENESES te envió 1 sol por Yape.",
            YapeSpeechFormatter.phrase(result.amount, result.sender, true),
        )
    }

    @Test
    fun processorAcceptsRealVariantsAndRejectsOutgoingOrNoise() {
        mapOf(
            "Yape! María López te envió un pago por S/ 25.50" to "María López",
            "Yape! José Pérez te envió un pago por S/ 1,20" to "José Pérez",
            "Confirmación de Pago - ANA MARÍA RAMOS te envió un pago por S/ 100" to "ANA MARÍA RAMOS",
            "Carlos te envió un pago por S/1" to "Carlos",
            "Recibiste S/ 20 de Carlos" to "Carlos",
            "Carlos te yapeó S/ 15" to "Carlos",
        ).forEach { (text, sender) ->
            val result = processor().process(payload(text = text, key = text))
            assertTrue(result is NotificationProcessingResult.PaymentReceived)
            result as NotificationProcessingResult.PaymentReceived
            assertEquals(sender, result.sender)
        }

        listOf(
            "Yape! Enviaste un pago por S/ 20",
            "Pago realizado por S/ 20",
            "Promoción: gana S/ 100",
            "Operación rechazada por S/ 20",
        ).forEach { text ->
            assertTrue(processor().process(payload(text = text, key = text)) is NotificationProcessingResult.Ignored)
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
        assertEquals("VICTOR MANUEL MENESES te envió 1 sol por Yape.", YapeSpeechFormatter.phrase(BigDecimal("1.00"), "VICTOR MANUEL MENESES", true))
        assertEquals("María López te envió 25 soles con 50 céntimos por Yape.", YapeSpeechFormatter.phrase(BigDecimal("25.50"), "María López", true))
        assertEquals("Recibiste 20 soles por Yape.", YapeSpeechFormatter.phrase(BigDecimal("20.00"), null, true))
        assertEquals("20 soles.", YapeSpeechFormatter.phrase(BigDecimal("20.00"), "Carlos", false))
        assertEquals("1 sol con 1 céntimo", YapeSpeechFormatter.amountText(BigDecimal("1.01")))
        assertEquals("25 soles con 50 céntimos", YapeSpeechFormatter.amountText(BigDecimal("25.50")))
    }

    @Test
    fun historyPersistsMostRecentHundredAndClears() {
        val repo = PaymentHistoryRepository(InMemoryKeyValueStore())
        repeat(105) {
            repo.add(PaymentRecord(it.toLong(), "$it.00", null, "Yape recibido. $it soles.", "test", true))
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

    @Test
    fun genericProcessorReadsOnlySelectedAppsAndBlocksSensitiveText() {
        val selected = AppSelection(
            packageName = "com.chat.test",
            label = "Chat",
            enabled = true,
            readMode = AppReadMode.TITLE_AND_CONTENT,
            detected = true,
        )
        val processor = NotificationProcessor(
            mapOf(selected.packageName to selected),
            NotificationDeduplicator(TimeProvider { 1_000L }),
        )

        val spoken = processor.process(
            payload(packageName = "com.chat.test", title = "María", text = "llego en diez minutos"),
        )
        assertTrue(spoken is NotificationProcessingResult.SpokenNotification)
        spoken as NotificationProcessingResult.SpokenNotification
        assertEquals("Chat. María: llego en diez minutos", spoken.spokenText)

        assertEquals(
            NotificationProcessingResult.Ignored(IgnoreReason.APP_NOT_SELECTED),
            processor.process(payload(packageName = "com.other", title = "Otro", text = "hola")),
        )
        assertEquals(
            NotificationProcessingResult.Ignored(IgnoreReason.PRIVACY_FILTERED),
            processor.process(payload(packageName = "com.chat.test", title = "OTP", text = "código de verificación 123456", key = "sensitive")),
        )
    }

    @Test
    fun appSelectionRepositoryRegistersDetectedAppsDisabled() {
        val repo = AppSelectionRepository(InMemoryKeyValueStore())
        repo.registerDetected("com.bank.test", "Banco")
        val app = repo.getAll().first { it.packageName == "com.bank.test" }
        assertEquals("Banco", app.label)
        assertFalse(app.enabled)
        assertEquals(AppReadMode.TITLE_AND_CONTENT, app.readMode)
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
            summaryText = null,
            textLines = textLines,
        )

    private data class ManualClock(var now: Long)
}
