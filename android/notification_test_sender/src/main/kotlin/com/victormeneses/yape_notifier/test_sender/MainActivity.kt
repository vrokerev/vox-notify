package com.victormeneses.yape_notifier.test_sender

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private var notificationCounter = 100

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createChannel()
        requestNotificationPermission()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 32)
        }
        root.addView(TextView(this).apply {
            text = "VoxNotify Test Sender"
            textSize = 22f
        })
        scenarios().forEach { scenario ->
            root.addView(Button(this).apply {
                text = scenario.label
                setOnClickListener { publish(scenario) }
            })
        }
        setContentView(ScrollView(this).apply { addView(root, ViewGroup.LayoutParams(-1, -2)) })
    }

    private fun scenarios(): List<Scenario> = listOf(
        Scenario(
            label = "Yape real VICTOR S/ 1",
            title = "Confirmación de Pago",
            text = "Yape! VICTOR MANUEL MENESES te envió un pago por S/ 1",
        ),
        Scenario("Pago recibido S/ 20", "Recibiste un Yape de S/ 20"),
        Scenario("Pago recibido S/ 25.50", "Te yapearon S/25.50"),
        Scenario("Pago recibido S/ 1.01", "Has recibido S/ 1.01"),
        Scenario("Pago recibido con texto expandido", "Yape", "Recibiste un Yape", "Monto recibido: S/ 45.70"),
        Scenario("Pago recibido usando textLines", "Yape", "Nuevo Yape", lines = listOf("Cliente", "S/ 33.20")),
        Scenario("Pago enviado", "Enviaste S/ 30"),
        Scenario("Promoción", "Promoción: gana S/ 100"),
        Scenario("Operación rechazada", "Operación rechazada por S/ 25"),
        Scenario("Notificación sin monto", "Recibiste dinero a las 10:30"),
        Scenario("Notificación duplicada", "Recibiste un Yape de S/ 20", fixedId = 777),
        Scenario("Dos pagos diferentes del mismo monto", "Recibiste un Yape de S/ 20"),
    )

    private fun publish(scenario: Scenario) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestNotificationPermission()
            return
        }
        val id = scenario.fixedId ?: notificationCounter++
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(scenario.title)
            .setContentText(scenario.text)
            .setAutoCancel(false)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            builder.setPriority(Notification.PRIORITY_HIGH)
        }
        if (scenario.bigText != null) {
            builder.setStyle(Notification.BigTextStyle().bigText(scenario.bigText))
        }
        if (scenario.lines.isNotEmpty()) {
            builder.setStyle(Notification.InboxStyle().also { style ->
                scenario.lines.forEach { style.addLine(it) }
            }).setExtras(Bundle().apply {
                putCharSequenceArray(Notification.EXTRA_TEXT_LINES, scenario.lines.toTypedArray())
            })
        }
        getSystemService(NotificationManager::class.java).notify(id, builder.build())
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Pruebas Yape", NotificationManager.IMPORTANCE_HIGH)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 10)
        }
    }

    companion object {
        private const val CHANNEL_ID = "yape_notifier_test_sender"
    }
}

private data class Scenario(
    val label: String,
    val text: String,
    val title: String = "Yape",
    val bigText: String? = null,
    val lines: List<String> = emptyList(),
    val fixedId: Int? = null,
)
