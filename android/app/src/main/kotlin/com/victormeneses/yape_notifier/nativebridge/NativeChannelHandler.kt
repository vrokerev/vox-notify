package com.victormeneses.yape_notifier.nativebridge

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.service.notification.NotificationListenerService
import com.victormeneses.yape_notifier.notifications.AllowedPackages
import com.victormeneses.yape_notifier.notifications.AppReadMode
import com.victormeneses.yape_notifier.notifications.AppSelection
import com.victormeneses.yape_notifier.notifications.NativeSettings
import com.victormeneses.yape_notifier.notifications.NotificationDeduplicator
import com.victormeneses.yape_notifier.notifications.NotificationPayload
import com.victormeneses.yape_notifier.notifications.NotificationProcessor
import com.victormeneses.yape_notifier.notifications.NotificationProcessingResult
import com.victormeneses.yape_notifier.notifications.PaymentRecord
import com.victormeneses.yape_notifier.notifications.TimeProvider
import com.victormeneses.yape_notifier.notifications.YapeSpeechFormatter
import com.victormeneses.yape_notifier.notifications.YapeNotificationListenerService
import com.victormeneses.yape_notifier.storage.AppSelectionRepository
import com.victormeneses.yape_notifier.storage.ListenerDiagnosticsRepository
import com.victormeneses.yape_notifier.speech.YapeTextToSpeech
import com.victormeneses.yape_notifier.storage.NativeSettingsRepository
import com.victormeneses.yape_notifier.storage.PaymentHistoryRepository
import com.victormeneses.yape_notifier.storage.SharedPreferencesStore
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.math.BigDecimal

class NativeChannelHandler(private val context: Context) : MethodChannel.MethodCallHandler {
    private val store = SharedPreferencesStore(context.applicationContext)
    private val settingsRepository = NativeSettingsRepository(store)
    private val historyRepository = PaymentHistoryRepository(store)
    private val appSelectionRepository = AppSelectionRepository(store)
    private val diagnosticsRepository = ListenerDiagnosticsRepository(store)
    private val speech by lazy { YapeTextToSpeech(context.applicationContext) }

    override fun onMethodCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "isNotificationAccessEnabled" -> result.success(NotificationAccess.isEnabled(context))
            "openNotificationAccessSettings" -> {
                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                result.success(null)
            }
            "getSettings" -> result.success(settingsRepository.get().toMap())
            "updateSettings" -> {
                val voiceEnabled = call.argument<Boolean>("voiceEnabled") ?: true
                val fullPhrase = call.argument<Boolean>("fullPhrase") ?: true
                result.success(settingsRepository.update(NativeSettings(voiceEnabled, fullPhrase)).toMap())
            }
            "testSpeech" -> {
                val settings = settingsRepository.get()
                val phrase = YapeSpeechFormatter.phrase(BigDecimal("20.50"), "María López", settings.fullPhrase)
                if (settings.voiceEnabled) speech.speak(phrase)
                result.success(phrase)
            }
            "getHistory" -> result.success(historyRepository.get().map { it.toMap() })
            "getListenerDiagnostics" -> result.success(diagnosticsRepository.get())
            "getAvailableApps" -> result.success(
                AppLabelResolver.visibleLauncherApps(context.applicationContext, appSelectionRepository)
                    .map { it.toMap() },
            )
            "updateAppSelection" -> {
                val packageName = call.argument<String>("packageName")
                if (packageName.isNullOrBlank()) {
                    result.error("invalid_package", "packageName is required", null)
                    return
                }
                val enabled = call.argument<Boolean>("enabled") ?: false
                val readMode = call.argument<String>("readMode")
                    ?.let { runCatching { AppReadMode.valueOf(it) }.getOrNull() }
                val label = call.argument<String>("label")
                result.success(appSelectionRepository.update(packageName, enabled, readMode, label).toMap())
            }
            "clearHistory" -> {
                historyRepository.clear()
                result.success(null)
            }
            "requestListenerRebind" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    NotificationListenerService.requestRebind(ComponentName(context, YapeNotificationListenerService::class.java))
                }
                result.success(null)
            }
            "runDebugPayload" -> result.success(runDebugPayload(call.argument<String>("text").orEmpty()))
            else -> result.notImplemented()
        }
    }

    private fun runDebugPayload(text: String): Map<String, Any?> {
        val now = System.currentTimeMillis()
        val payload = NotificationPayload(
            packageName = AllowedPackages.TEST_SENDER_PACKAGE,
            notificationKey = "flutter-debug-$now",
            notificationId = now.toInt(),
            postTime = now,
            title = "Yape",
            text = text,
            bigText = null,
            subText = null,
            infoText = null,
            summaryText = null,
            textLines = emptyList(),
        )
        val processor = NotificationProcessor(
            appSelectionRepository.getEnabledMap(),
            NotificationDeduplicator(TimeProvider { now }),
        )
        val processed = processor.process(payload)
        return when (processed) {
            is NotificationProcessingResult.PaymentReceived -> {
                val settings = settingsRepository.get()
                val phrase = YapeSpeechFormatter.phrase(processed.amount, processed.sender, settings.fullPhrase)
                historyRepository.add(PaymentRecord(now, processed.amount.toPlainString(), processed.sender, phrase, "debug", settings.voiceEnabled))
                if (settings.voiceEnabled) speech.speak(phrase)
                mapOf("accepted" to true, "amount" to processed.amount.toPlainString(), "sender" to processed.sender, "speech" to phrase)
            }
            is NotificationProcessingResult.SpokenNotification -> {
                historyRepository.add(PaymentRecord(now, "", null, processed.spokenText, processed.appLabel, settingsRepository.get().voiceEnabled))
                if (settingsRepository.get().voiceEnabled) speech.speak(processed.spokenText)
                mapOf("accepted" to true, "speech" to processed.spokenText)
            }
            is NotificationProcessingResult.Ignored -> mapOf("accepted" to false, "reason" to processed.reason.name)
        }
    }

    companion object {
        const val CHANNEL_NAME = "yape_notifier/native"
    }
}

private fun NativeSettings.toMap(): Map<String, Any> =
    mapOf("voiceEnabled" to voiceEnabled, "fullPhrase" to fullPhrase)

private fun PaymentRecord.toMap(): Map<String, Any> =
    mapOf(
        "timestamp" to timestamp,
        "amount" to amount,
        "sender" to sender.orEmpty(),
        "spokenText" to spokenText,
        "source" to source,
        "announced" to announced,
    )

private fun AppSelection.toMap(): Map<String, Any> =
    mapOf(
        "packageName" to packageName,
        "label" to label,
        "enabled" to enabled,
        "readMode" to readMode.name,
        "detected" to detected,
    )
