import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yape_notifier/main.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('yape_notifier/native');
  const events = EventChannel('voxnotify/events');
  var notificationAccess = true;

  setUp(() {
    notificationAccess = true;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          switch (call.method) {
            case 'isNotificationAccessEnabled':
              return notificationAccess;
            case 'getSettings':
              return {
                'voiceEnabled': true,
                'fullPhrase': true,
                'continuousBackground': true,
              };
            case 'getHistory':
              return [
                {
                  'timestamp': 1,
                  'amount': '20.00',
                  'sender': 'VICTOR MANUEL MENESES',
                  'spokenText':
                      'VICTOR MANUEL MENESES te envió 20 soles por Yape.',
                  'source': 'debug',
                  'announced': true,
                },
              ];
            case 'getListenerDiagnostics':
              return {
                'listenerConnected': 'true',
                'listenerLive': 'true',
                'listenerFreshness': 'active',
                'listenerHealthCheckResult': 'ok:active=1',
                'listenerRecoveryState': 'healthy',
                'foregroundSpeechServiceActuallyRunning': 'true',
                'lastCallbackPackage': 'com.bcp.innovacxion.yapeapp',
                'lastProcessingResult': 'YAPE_ANNOUNCED',
                'ttsState': 'ready',
                'manufacturer': 'Xiaomi',
                'yapePackageLabel': 'Yape',
              };
            case 'getBatteryOptimizationIgnored':
              return true;
            case 'isContinuousBackgroundRunning':
              return true;
            case 'hasPostNotificationsPermission':
              return true;
            case 'getAvailableApps':
              return [
                {
                  'packageName': 'com.bcp.innovacxion.yapeapp',
                  'label': 'Yape',
                  'enabled': true,
                  'readMode': 'SMART_YAPE',
                  'detected': true,
                  'installed': true,
                  'userProfile': 'personal',
                },
                {
                  'packageName': 'com.mail.test',
                  'label': 'Correo',
                  'enabled': false,
                  'readMode': 'TITLE_AND_CONTENT',
                  'detected': true,
                  'installed': true,
                  'userProfile': 'personal',
                },
              ];
            case 'requestListenerRebind':
            case 'clearHistory':
            case 'openNotificationAccessSettings':
            case 'openAppDetailsSettings':
            case 'openBatterySettings':
            case 'openBatteryOptimizationSettings':
            case 'openXiaomiAutostartSettings':
              return null;
            case 'requestPostNotificationsPermission':
              return true;
            case 'testSpeech':
              return 'María López te envió 20 soles con 50 céntimos por Yape.';
            case 'updateSettings':
              return {
                'voiceEnabled': true,
                'fullPhrase': true,
                'continuousBackground': true,
                'continuousBackgroundRunning': true,
                'postNotificationsPermissionGranted': true,
              };
            case 'updateAppSelection':
              return <String, Object?>{};
            case 'startContinuousBackground':
              return {
                'continuousBackground': true,
                'continuousBackgroundRunning': true,
                'postNotificationsPermissionGranted': true,
              };
            case 'stopContinuousBackground':
              return {
                'continuousBackground': false,
                'continuousBackgroundRunning': false,
                'postNotificationsPermissionGranted': true,
              };
            case 'runListenerProbe':
              return {
                'posted': true,
                'listenerReceived': true,
              };
            case 'runDebugPayload':
              return {
                'accepted': true,
                'amount': '20.00',
                'speech': 'Yape recibido. 20 soles.',
              };
          }
          return null;
        });

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockStreamHandler(
          events,
          MockStreamHandler.inline(onListen: (arguments, events) {}),
        );
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockStreamHandler(events, null);
  });

  testWidgets('renders a simple healthy dashboard', (tester) async {
    await tester.pumpWidget(const VoxNotifyApp());
    await tester.pumpAndSettle();

    expect(find.text('VoxNotify'), findsOneWidget);
    expect(find.text('Estado de VoxNotify'), findsOneWidget);
    expect(
      find.text('Todo listo para leer tus notificaciones'),
      findsOneWidget,
    );
    expect(find.text('Voz activa'), findsOneWidget);
    expect(find.text('Aplicaciones para leer'), findsOneWidget);
    expect(find.text('Yape'), findsNothing);
  });

  testWidgets('loads apps only when the apps panel is expanded', (tester) async {
    await tester.pumpWidget(const VoxNotifyApp());
    await tester.pumpAndSettle();

    await tester.tap(find.text('Aplicaciones para leer'));
    await tester.pumpAndSettle();

    expect(find.text('Yape'), findsOneWidget);
    expect(find.text('Qué debe leer'), findsOneWidget);
    expect(find.text('Aplicaciones detectadas (1)'), findsOneWidget);
  });

  testWidgets('highlights missing notification access', (tester) async {
    notificationAccess = false;

    await tester.pumpWidget(const VoxNotifyApp());
    await tester.pumpAndSettle();

    expect(
      find.text('Acceso a notificaciones deshabilitado'),
      findsOneWidget,
    );
    expect(find.text('Habilitar acceso'), findsOneWidget);
  });
}
