package com.victormeneses.yape_notifier.nativebridge

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
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
import com.victormeneses.yape_notifier.notifications.VoxNotifyForegroundService
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
                val continuousBackground = call.argument<Boolean>("continuousBackground")
                    ?: settingsRepository.get().continuousBackground
                val updated = settingsRepository.update(NativeSettings(voiceEnabled, fullPhrase, continuousBackground))
                if (updated.continuousBackground) {
                    VoxNotifyForegroundService.start(context.applicationContext)
                } else {
                    VoxNotifyForegroundService.stop(context.applicationContext)
                }
                result.success(updated.toMap())
            }
            "testSpeech" -> {
                val settings = settingsRepository.get()
                val phrase = YapeSpeechFormatter.phrase(BigDecimal("20.50"), "María López", settings.fullPhrase)
                if (settings.voiceEnabled) speech.speak(phrase)
                result.success(phrase)
            }
            "getHistory" -> result.success(historyRepository.get().map { it.toMap() })
            "getListenerDiagnostics" -> result.success(listenerDiagnostics())
            "hasPostNotificationsPermission" -> result.success(hasPostNotificationsPermission())
            "requestPostNotificationsPermission" -> {
                requestPostNotificationsPermission()
                result.success(hasPostNotificationsPermission())
            }
            "isContinuousBackgroundRunning" -> result.success(VoxNotifyForegroundService.isRunning())
            "getBatteryOptimizationIgnored" -> {
                val powerManager = context.getSystemService(PowerManager::class.java)
                result.success(powerManager.isIgnoringBatteryOptimizations(context.packageName))
            }
            "getPackageLabel" -> {
                val packageName = call.argument<String>("packageName").orEmpty()
                result.success(AppLabelResolver.labelFor(context.applicationContext, packageName))
            }
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
            "startContinuousBackground" -> {
                val current = settingsRepository.get()
                settingsRepository.update(current.copy(continuousBackground = true))
                VoxNotifyForegroundService.start(context.applicationContext)
                result.success(settingsWithRuntimeState())
            }
            "stopContinuousBackground" -> {
                val current = settingsRepository.get()
                settingsRepository.update(current.copy(continuousBackground = false))
                VoxNotifyForegroundService.stop(context.applicationContext)
                result.success(settingsWithRuntimeState())
            }
            "openAppDetailsSettings" -> {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                result.success(null)
            }
            "openBatterySettings" -> {
                val intent = Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
                    .onFailure {
                        context.startActivity(
                            Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                result.success(null)
            }
            "openBatteryOptimizationSettings" -> {
                context.startActivity(
                    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                result.success(null)
            }
            "openXiaomiAutostartSettings" -> {
                openXiaomiAutostartSettings()
                result.success(null)
            }
            "runDebugPayload" -> result.success(runDebugPayload(call.argument<String>("text").orEmpty()))
            else -> result.notImplemented()
        }
    }

    private fun hasPostNotificationsPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun requestPostNotificationsPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val activity = context as? Activity ?: return
        if (hasPostNotificationsPermission()) return
        activity.requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), POST_NOTIFICATIONS_REQUEST_CODE)
    }

    private fun listenerDiagnostics(): Map<String, String> {
        val diagnostics = diagnosticsRepository.get().toMutableMap()
        val now = System.currentTimeMillis()
        val listenerConnectedAt = diagnostics["listenerConnectedAt"].orEmpty().toLongOrNull() ?: 0L
        val lastCallbackAt = diagnostics["lastCallbackAt"].orEmpty().toLongOrNull() ?: 0L
        val foregroundHeartbeatAt = diagnostics["foregroundHeartbeatAt"].orEmpty().toLongOrNull() ?: 0L
        val foregroundLive = VoxNotifyForegroundService.isRunning() ||
            (foregroundHeartbeatAt > 0L && now - foregroundHeartbeatAt <= FOREGROUND_HEARTBEAT_STALE_MS)
        val listenerLive = diagnostics["listenerConnected"] == "true" &&
            listenerConnectedAt > 0L &&
            now - listenerConnectedAt <= LISTENER_CONNECTION_STALE_MS
        diagnostics["listenerLive"] = listenerLive.toString()
        diagnostics["listenerFreshness"] = freshness(now, listenerConnectedAt, LISTENER_CONNECTION_STALE_MS)
        diagnostics["lastCallbackFreshness"] = freshness(now, lastCallbackAt, LAST_CALLBACK_STALE_MS)
        diagnostics["foregroundSpeechServiceActuallyRunning"] = foregroundLive.toString()
        diagnostics["foregroundHeartbeatFreshness"] = freshness(now, foregroundHeartbeatAt, FOREGROUND_HEARTBEAT_STALE_MS)
        diagnostics["postNotificationsPermissionGranted"] = hasPostNotificationsPermission().toString()
        diagnostics["yapePackageLabel"] = AppLabelResolver.labelFor(context.applicationContext, AllowedPackages.YAPE_PACKAGE)
        return diagnostics
    }

    private fun settingsWithRuntimeState(): Map<String, Any> =
        settingsRepository.get().toMap() +
            mapOf(
                "continuousBackgroundRunning" to VoxNotifyForegroundService.isRunning(),
                "postNotificationsPermissionGranted" to hasPostNotificationsPermission(),
            )

    private fun freshness(now: Long, timestamp: Long, staleAfter: Long): String =
        when {
            timestamp <= 0L -> "unknown"
            now - timestamp <= staleAfter -> "fresh"
            else -> "stale"
        }

    private fun openXiaomiAutostartSettings() {
        val intents = listOf(
            Intent().setClassName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity",
            ),
            Intent().setClassName(
                "com.miui.securitycenter",
                "com.miui.powercenter.PowerSettings",
            ),
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).setData(Uri.parse("package:${context.packageName}")),
        )
        val intent = intents.firstOrNull {
            runCatching { it.resolveActivity(context.packageManager) != null }.getOrDefault(false)
        } ?: Intent(Settings.ACTION_SETTINGS)
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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
        const val EVENT_CHANNEL_NAME = "voxnotify/events"
        private const val POST_NOTIFICATIONS_REQUEST_CODE = 4202
        private const val LISTENER_CONNECTION_STALE_MS = 10 * 60 * 1000L
        private const val LAST_CALLBACK_STALE_MS = 24 * 60 * 60 * 1000L
        private const val FOREGROUND_HEARTBEAT_STALE_MS = 45 * 1000L
    }
}

private fun NativeSettings.toMap(): Map<String, Any> =
    mapOf(
        "voiceEnabled" to voiceEnabled,
        "fullPhrase" to fullPhrase,
        "continuousBackground" to continuousBackground,
    )

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
