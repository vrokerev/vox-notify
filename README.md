# Yape Notifier

Aplicación Flutter/Android que escucha localmente las notificaciones de Yape y anuncia en voz alta los pagos recibidos con TextToSpeech nativo en español de Perú.

## Privacidad

Todo el procesamiento ocurre en el teléfono. La app principal no usa Firebase, servidores, analíticas ni internet, y no solicita permisos de contactos, SMS, llamadas, ubicación, cámara, micrófono ni archivos.

## Paquetes permitidos

En release solo se procesan notificaciones de:

```text
com.bcp.innovacxion.yapeapp
```

En debug también se permite el emisor local de pruebas:

```text
com.victormeneses.yape_notifier.test_sender
```

Android no permite suplantar legítimamente el paquete oficial de Yape; por eso el proyecto incluye un segundo APK debug para publicar notificaciones reales del sistema durante desarrollo.

## Comandos de validación

```powershell
dart format .
flutter pub get
flutter analyze
flutter test
cd android
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
cd ..
flutter build apk --debug
```

Con un dispositivo o emulador conectado:

```powershell
cd android
.\gradlew.bat connectedDebugAndroidTest
```

## Prueba manual real

1. Compila e instala la app principal en debug.
2. Compila e instala `Yape Notifier Test Sender`.
3. Abre `Yape Notifier`.
4. Pulsa `Habilitar acceso a notificaciones`.
5. Activa manualmente el acceso para `Yape Notifier`.
6. Vuelve a la aplicación.
7. Confirma que aparezca `Acceso habilitado`.
8. Abre `Yape Notifier Test Sender`.
9. Envía una notificación de pago recibido.
10. Verifica que la app principal diga el monto.
11. Verifica que el pago aparezca en el historial.
12. Envía una notificación de pago enviado.
13. Verifica que no se reproduzca ninguna voz.
14. Envía dos pagos diferentes con el mismo monto.
15. Verifica que ambos sean procesados.
16. Envía la misma notificación duplicada.
17. Verifica que solo se procese una vez dentro de la ventana de deduplicación.
18. Cierra completamente la interfaz Flutter.
19. Envía otra notificación válida desde el emisor.
20. Verifica que el listener nativo siga anunciando el monto.

## APKs esperados

App principal:

```text
build/app/outputs/flutter-apk/app-debug.apk
```

Emisor de pruebas:

```text
android/notification_test_sender/build/outputs/apk/debug/notification_test_sender-debug.apk
```
