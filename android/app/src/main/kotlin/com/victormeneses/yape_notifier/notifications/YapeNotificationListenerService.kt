package com.victormeneses.yape_notifier.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.victormeneses.yape_notifier.BuildConfig
import com.victormeneses.yape_notifier.speech.YapeTextToSpeech
import com.victormeneses.yape_notifier.storage.NativeSettingsRepository
import com.victormeneses.yape_notifier.storage.PaymentHistoryRepository
import com.victormeneses.yape_notifier.storage.SharedPreferencesStore

class YapeNotificationListenerService : NotificationListenerService() {
    private lateinit var settingsRepository: NativeSettingsRepository
    private lateinit var historyRepository: PaymentHistoryRepository
    private lateinit var processor: YapeNotificationProcessor
    private lateinit var speech: YapeTextToSpeech

    override fun onCreate() {
        super.onCreate()
        val store = SharedPreferencesStore(applicationContext)
        settingsRepository = NativeSettingsRepository(store)
        historyRepository = PaymentHistoryRepository(store)
        processor = YapeNotificationProcessor(
            AllowedPackages.current(),
            NotificationDeduplicator(TimeProvider { System.currentTimeMillis() }),
        )
        speech = YapeTextToSpeech(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val payload = NotificationTextExtractor.fromNotification(
            packageName = sbn.packageName,
            key = sbn.key,
            id = sbn.id,
            postTime = sbn.postTime,
            notification = sbn.notification,
        )
        val result = processor.process(payload)
        if (BuildConfig.DEBUG) {
            Log.d(TAG, "package=${payload.packageName} result=${result.javaClass.simpleName}")
        }
        if (result is NotificationProcessingResult.PaymentReceived) {
            val settings = settingsRepository.get()
            val phrase = YapeSpeechFormatter.phrase(result.amount, settings.fullPhrase)
            historyRepository.add(
                PaymentRecord(
                    timestamp = System.currentTimeMillis(),
                    amount = result.amount.toPlainString(),
                    spokenText = phrase,
                    source = payload.packageName,
                ),
            )
            if (settings.voiceEnabled) {
                speech.speak(phrase)
            }
        } else if (BuildConfig.DEBUG && result is NotificationProcessingResult.Ignored) {
            Log.d(TAG, "ignored=${result.reason.name}")
        }
    }

    override fun onDestroy() {
        speech.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "YapeNotifier"
    }
}
