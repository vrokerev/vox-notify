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
import com.victormeneses.yape_notifier.storage.VoxNotifyEventBus

class YapeNotificationListenerService : NotificationListenerService() {
    private lateinit var settingsRepository: NativeSettingsRepository
    private lateinit var historyRepository: PaymentHistoryRepository
    private lateinit var appSelectionRepository: AppSelectionRepository
    private lateinit var diagnosticsRepository: ListenerDiagnosticsRepository
    private lateinit var deduplicator: NotificationDeduplicator
    private lateinit var speech: YapeTextToSpeech
    private lateinit var rebindScheduler: ListenerRebindScheduler

    override fun onCreate() {
        super.onCreate()
        val store = SharedPreferencesStore(applicationContext)
        settingsRepository = NativeSettingsRepository(store)
        historyRepository = PaymentHistoryRepository(store)
        appSelectionRepository = AppSelectionRepository(store)
        diagnosticsRepository = ListenerDiagnosticsRepository(store)
        diagnosticsRepository.update(
            mapOf(
                "processCreatedAt" to System.currentTimeMillis().toString(),
                "manufacturer" to android.os.Build.MANUFACTURER,
                "appStandbyBucket" to standbyBucket(),
                "batteryOptimizationIgnored" to batteryOptimizationIgnored(),
            ),
        )
        deduplicator = NotificationDeduplicator(
            TimeProvider { System.currentTimeMillis() },
            store = store,
        )
        speech = YapeTextToSpeech(applicationContext, diagnosticsRepository)
        rebindScheduler = ListenerRebindScheduler(applicationContext, diagnosticsRepository)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        rebindScheduler.cancel()
        diagnosticsRepository.update(
            mapOf(
                "listenerConnected" to "true",
                "listenerConnectedAt" to System.currentTimeMillis().toString(),
                "ttsState" to "created",
                "batteryOptimizationIgnored" to batteryOptimizationIgnored(),
                "appStandbyBucket" to standbyBucket(),
            ),
        )
        registerActiveNotificationPackages()
        VoxNotifyEventBus.emit("listener_connected")
        if (BuildConfig.DEBUG) Log.d(TAG, "listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        diagnosticsRepository.update(
            mapOf(
                "listenerConnected" to "false",
                "listenerDisconnectedAt" to System.currentTimeMillis().toString(),
            ),
        )
        VoxNotifyEventBus.emit("listener_disconnected")
        rebindScheduler.schedule("onListenerDisconnected")
        if (BuildConfig.DEBUG) Log.d(TAG, "listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val callbackAt = System.currentTimeMillis()
        diagnosticsRepository.update(
            mapOf(
                "lastCallbackAt" to callbackAt.toString(),
                "lastCallbackPackage" to sbn.packageName,
                "lastProcessingResult" to "callback_received",
            ),
        )
        VoxNotifyEventBus.emit("notification_callback")
        val payload = NotificationTextExtractor.fromNotification(
            packageName = sbn.packageName,
            key = sbn.key,
            id = sbn.id,
            postTime = sbn.postTime,
            notification = sbn.notification,
        )
        if (payload.packageName == AllowedPackages.YAPE_PACKAGE) {
            diagnosticsRepository.update(yapeDebugFields(payload, "callback_received"))
        }
        val detection = appSelectionRepository.registerDetected(
            payload.packageName,
            AppLabelResolver.labelFor(applicationContext, payload.packageName),
        )
        if (BuildConfig.DEBUG) {
            Log.d(
                TAG,
                "detected package=${payload.packageName} existed=${detection.existed} previousDetected=${detection.previousDetected} finalDetected=${detection.finalDetected} changed=${detection.changed} origin=onNotificationPosted",
            )
        }
        val processor = NotificationProcessor(appSelectionRepository.getEnabledMap(), deduplicator)
        val result = processor.process(payload)
        diagnosticsRepository.update(mapOf("lastProcessingResult" to result.javaClass.simpleName))
        if (payload.packageName == AllowedPackages.YAPE_PACKAGE) {
            diagnosticsRepository.update(
                mapOf("lastYapeParserResult" to processingSummary(result)),
            )
        }
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
                    "lastSpokenText" to phrase,
                    "ttsState" to if (settings.voiceEnabled) "speaking_or_queued" else "voice_disabled",
                ),
            )
            if (settings.voiceEnabled) {
                diagnosticsRepository.update(mapOf("lastTtsQueuedAt" to System.currentTimeMillis().toString()))
                VoxNotifyEventBus.emit("tts_queued")
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
                diagnosticsRepository.update(
                    mapOf(
                        "lastSpokenText" to result.spokenText,
                        "lastTtsQueuedAt" to System.currentTimeMillis().toString(),
                    ),
                )
                VoxNotifyEventBus.emit("tts_queued")
                speech.speak(result.spokenText)
            }
        } else if (result is NotificationProcessingResult.Ignored) {
            if (BuildConfig.DEBUG) Log.d(TAG, "ignored=${result.reason.name}")
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
        VoxNotifyEventBus.emit("notification_processed")
    }

    override fun onDestroy() {
        speech.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "VoxNotify"
    }

    private fun registerActiveNotificationPackages() {
        val active = runCatching { activeNotifications ?: emptyArray() }.getOrDefault(emptyArray())
        active.forEach { sbn ->
            val label = AppLabelResolver.labelFor(applicationContext, sbn.packageName)
            val detection = appSelectionRepository.registerDetected(sbn.packageName, label)
            if (BuildConfig.DEBUG && detection.changed) {
                Log.d(TAG, "detected package=${sbn.packageName} changed=true origin=activeNotifications")
            }
        }
    }

    private fun batteryOptimizationIgnored(): String {
        val powerManager = getSystemService(android.os.PowerManager::class.java)
        return powerManager.isIgnoringBatteryOptimizations(packageName).toString()
    }

    private fun standbyBucket(): String =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            getSystemService(android.app.usage.UsageStatsManager::class.java).appStandbyBucket.toString()
        } else {
            "unsupported"
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

    private fun yapeDebugFields(payload: NotificationPayload, parserResult: String): Map<String, String?> =
        mapOf(
            "lastYapePackageName" to payload.packageName,
            "lastYapePostTime" to payload.postTime.toString(),
            "lastYapeTitle" to payload.title.orEmpty(),
            "lastYapeText" to payload.text.orEmpty(),
            "lastYapeBigText" to payload.bigText.orEmpty(),
            "lastYapeSubText" to payload.subText.orEmpty(),
            "lastYapeSummaryText" to payload.summaryText.orEmpty(),
            "lastYapeTextLines" to payload.textLines.joinToString(" | "),
            "lastYapeParserResult" to parserResult,
        )

    private fun processingSummary(result: NotificationProcessingResult): String =
        when (result) {
            is NotificationProcessingResult.PaymentReceived ->
                "payment_received amount=${result.amount.toPlainString()} sender=${result.sender.orEmpty()}"
            is NotificationProcessingResult.SpokenNotification -> "spoken_notification"
            is NotificationProcessingResult.Ignored -> "ignored:${result.reason.name}"
        }
}
