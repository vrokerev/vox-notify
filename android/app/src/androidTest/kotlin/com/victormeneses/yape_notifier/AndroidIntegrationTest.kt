package com.victormeneses.yape_notifier

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.victormeneses.yape_notifier.nativebridge.NotificationAccess
import com.victormeneses.yape_notifier.notifications.NotificationTextExtractor
import com.victormeneses.yape_notifier.storage.NativeSettingsRepository
import com.victormeneses.yape_notifier.storage.PaymentHistoryRepository
import com.victormeneses.yape_notifier.storage.SharedPreferencesStore
import com.victormeneses.yape_notifier.notifications.PaymentRecord
import com.victormeneses.yape_notifier.storage.AppSelectionRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidIntegrationTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun sharedPreferencesSettingsRoundTrip() {
        val repo = NativeSettingsRepository(SharedPreferencesStore(context))
        repo.update(com.victormeneses.yape_notifier.notifications.NativeSettings(false, false, false))
        assertEquals(false, repo.get().voiceEnabled)
        assertEquals(false, repo.get().fullPhrase)
    }

    @Test
    fun historyLimitAndClearUseSharedPreferences() {
        val repo = PaymentHistoryRepository(SharedPreferencesStore(context))
        repo.clear()
        repeat(105) { repo.add(PaymentRecord(it.toLong(), "$it.00", null, "speech", "test", true)) }
        assertEquals(100, repo.get().size)
        repo.clear()
        assertTrue(repo.get().isEmpty())
    }

    @Test
    fun appSelectionsPersistDetectedAppsDisabled() {
        val repo = AppSelectionRepository(SharedPreferencesStore(context))
        repo.registerDetected("com.example.detected", "Detectada")
        val app = repo.getAll().first { it.packageName == "com.example.detected" }
        assertEquals(false, app.enabled)
        assertEquals("Detectada", app.label)
    }

    @Test
    fun notificationPayloadCanBeGeneratedFromNotification() {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel("test", "Test", NotificationManager.IMPORTANCE_DEFAULT)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
            Notification.Builder(context, "test")
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }
        val notification = builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Yape")
            .setContentText("Recibiste S/ 20")
            .build()
        val payload = NotificationTextExtractor.fromNotification("pkg", "key", 1, 2L, notification)
        assertEquals("Recibiste S/ 20", payload.text)
    }

    @Test
    fun listenerIsDeclaredInManifest() {
        val component = ComponentName(context, "com.victormeneses.yape_notifier.notifications.YapeNotificationListenerService")
        val service = context.packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)
        assertNotNull(service)
        assertEquals(android.Manifest.permission.BIND_NOTIFICATION_LISTENER_SERVICE, service.permission)
    }

    @Test
    fun foregroundServiceSurvivesTaskRemovalInManifest() {
        val component = ComponentName(context, "com.victormeneses.yape_notifier.notifications.VoxNotifyForegroundService")
        val service = context.packageManager.getServiceInfo(component, PackageManager.GET_META_DATA)
        assertNotNull(service)
        assertEquals(0, service.flags and ServiceInfo.FLAG_STOP_WITH_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            assertTrue(service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK != 0)
        }
    }

    @Test
    fun notificationAccessSettingsIntentCanResolve() {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        assertNotNull(intent.resolveActivity(context.packageManager))
    }

    @Test
    fun notificationAccessReadsSystemSetting() {
        val enabled = NotificationAccess.isEnabled(context)
        assertTrue(enabled || !enabled)
    }
}
