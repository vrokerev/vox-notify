# VoxNotify

Aplicación Flutter/Android que escucha localmente notificaciones seleccionadas por el usuario y las anuncia con TextToSpeech nativo en español de Perú. Yape se mantiene como integración inteligente para detectar pagos recibidos, remitente y monto de forma natural.

## Privacidad

Todo el procesamiento ocurre en el teléfono. La app principal no usa Firebase, servidores, analíticas ni internet, y no solicita permisos de contactos, SMS, llamadas, ubicación, cámara, micrófono ni archivos. Las aplicaciones genéricas deben activarse manualmente y los textos con expresiones sensibles como OTP, contraseña, clave, token o código de verificación se bloquean por defecto.

## Paquetes permitidos

Yape usa un parser especializado. En release, esa integración inteligente solo acepta:

```text
com.bcp.innovacxion.yapeapp
```

En debug también se permite el emisor local de pruebas para validar el flujo de pago:

```text
com.victormeneses.yape_notifier.test_sender
```

Android no permite suplantar legítimamente el paquete oficial de Yape; por eso el proyecto incluye un segundo APK debug para publicar notificaciones reales del sistema durante desarrollo.

## Lector configurable

La app muestra tres grupos:

- Aplicaciones activadas.
- Aplicaciones detectadas por el listener.
- Aplicaciones visibles como launchers según la visibilidad permitida por Android.

No se usa `QUERY_ALL_PACKAGES`. En Android 11 o superior el inventario completo puede estar filtrado por el sistema, así que las apps que no aparezcan inicialmente se agregan cuando emiten una notificación por primera vez. VoxNotify declara visibilidad explícita para WhatsApp estándar y Business, y cada app genérica puede leerse en modo `Título y mensaje`, `Solo título` o `Remitente`.

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
2. Compila e instala `VoxNotify Test Sender`.
3. Abre `VoxNotify`.
4. Pulsa `Habilitar acceso a notificaciones`.
5. Activa manualmente el acceso para `VoxNotify`.
6. Vuelve a la aplicación.
7. Confirma que aparezca `Acceso habilitado`.
8. Abre `VoxNotify Test Sender`.
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
