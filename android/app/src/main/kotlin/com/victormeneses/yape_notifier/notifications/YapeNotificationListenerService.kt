package com.victormeneses.yape_notifier.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.victormeneses.yape_notifier.BuildConfig
import com.victormeneses.yape_notifier.nativebridge.AppLabelResolver
import com.victormeneses.yape_notifier.speech.YapeTextToSpeech
import com.victormeneses.yape_notifier.storage.AppSelectionRepository
import com.victormeneses.yape_notifier.storage.ListenerDiagnosticsRepository
import com.victormeneses.yape_notifier.storage.NativeSettingsRepository
import com.victormeneses.yape_notifier.storage.PaymentHistoryRepository
import com.victormeneses.yape_notifier.storage.SharedPreferencesStore

class YapeNotificationListenerService : NotificationListenerService() {
    private lateinit var settingsRepository: NativeSettingsRepository
    private lateinit var historyRepository: PaymentHistoryRepository
    private lateinit var appSelectionRepository: AppSelectionRepository
    private lateinit var diagnosticsRepository: ListenerDiagnosticsRepository
    private lateinit var deduplicator: NotificationDeduplicator
    private lateinit var speech: YapeTextToSpeech

    override fun onCreate() {
        super.onCreate()
        val store = SharedPreferencesStore(applicationContext)
        settingsRepository = NativeSettingsRepository(store)
        historyRepository = PaymentHistoryRepository(store)
        appSelectionRepository = AppSelectionRepository(store)
        diagnosticsRepository = ListenerDiagnosticsRepository(store)
        deduplicator = NotificationDeduplicator(TimeProvider { System.currentTimeMillis() })
        speech = YapeTextToSpeech(applicationContext)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        diagnosticsRepository.update(
            mapOf(
                "listenerConnected" to "true",
                "ttsState" to "created",
            ),
        )
        if (BuildConfig.DEBUG) Log.d(TAG, "listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        diagnosticsRepository.update(mapOf("listenerConnected" to "false"))
        if (BuildConfig.DEBUG) Log.d(TAG, "listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val payload = NotificationTextExtractor.fromNotification(
            packageName = sbn.packageName,
            key = sbn.key,
            id = sbn.id,
            postTime = sbn.postTime,
            notification = sbn.notification,
        )
        appSelectionRepository.registerDetected(
            payload.packageName,
            AppLabelResolver.labelFor(applicationContext, payload.packageName),
        )
        val processor = NotificationProcessor(appSelectionRepository.getEnabledMap(), deduplicator)
        val result = processor.process(payload)
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "package=${payload.packageName} title=${payload.title.orEmpty()} fields=${presentFields(payload)} result=${result.javaClass.simpleName}",
            )
        }
        if (result is NotificationProcessingResult.PaymentReceived) {
            val settings = settingsRepository.get()
            val phrase = YapeSpeechFormatter.phrase(result.amount, result.sender, settings.fullPhrase)
            historyRepository.add(
                PaymentRecord(
                    timestamp = System.currentTimeMillis(),
                    amount = result.amount.toPlainString(),
                    sender = result.sender,
                    spokenText = phrase,
                    source = payload.packageName,
                    announced = settings.voiceEnabled,
                ),
            )
            diagnosticsRepository.update(
                mapOf(
                    "lastNotificationAt" to System.currentTimeMillis().toString(),
                    "lastPackage" to payload.packageName,
                    "lastDiscardReason" to "",
                    "lastAmount" to result.amount.toPlainString(),
                    "lastSender" to result.sender.orEmpty(),
                    "ttsState" to if (settings.voiceEnabled) "speaking_or_queued" else "voice_disabled",
                ),
            )
            if (settings.voiceEnabled) {
                speech.speak(phrase)
            }
        } else if (result is NotificationProcessingResult.SpokenNotification) {
            val settings = settingsRepository.get()
            historyRepository.add(
                PaymentRecord(
                    timestamp = System.currentTimeMillis(),
                    amount = "",
                    sender = null,
                    spokenText = result.spokenText,
                    source = result.appLabel,
                    announced = settings.voiceEnabled,
                ),
            )
            if (settings.voiceEnabled) {
                speech.speak(result.spokenText)
            }
        } else if (BuildConfig.DEBUG && result is NotificationProcessingResult.Ignored) {
            Log.d(TAG, "ignored=${result.reason.name}")
            diagnosticsRepository.update(
                mapOf(
                    "lastNotificationAt" to System.currentTimeMillis().toString(),
                    "lastPackage" to payload.packageName,
                    "lastDiscardReason" to result.reason.name,
                    "lastAmount" to "",
                    "lastSender" to "",
                ),
            )
        }
    }

    override fun onDestroy() {
        speech.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "YapeNotifier"
    }

    private fun presentFields(payload: NotificationPayload): String =
        listOfNotNull(
            "title".takeIf { !payload.title.isNullOrBlank() },
            "text".takeIf { !payload.text.isNullOrBlank() },
            "bigText".takeIf { !payload.bigText.isNullOrBlank() },
            "subText".takeIf { !payload.subText.isNullOrBlank() },
            "infoText".takeIf { !payload.infoText.isNullOrBlank() },
            "summaryText".takeIf { !payload.summaryText.isNullOrBlank() },
            "textLines".takeIf { payload.textLines.isNotEmpty() },
        ).joinToString(",")
}
