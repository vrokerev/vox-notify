import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yape_notifier/main.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('yape_notifier/native');
  const events = EventChannel('voxnotify/events');

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          switch (call.method) {
            case 'isNotificationAccessEnabled':
              return true;
            case 'getSettings':
              return {
                'voiceEnabled': true,
                'fullPhrase': true,
                'continuousBackground': false,
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
                'lastPackage': 'com.bcp.innovacxion.yapeapp',
                'manufacturer': 'Xiaomi',
              };
            case 'getBatteryOptimizationIgnored':
              return false;
            case 'getAvailableApps':
              return [
                {
                  'packageName': 'com.bcp.innovacxion.yapeapp',
                  'label': 'Yape',
                  'enabled': true,
                  'readMode': 'SMART_YAPE',
                  'detected': false,
                },
                {
                  'packageName': 'com.mail.test',
                  'label': 'Correo',
                  'enabled': false,
                  'readMode': 'TITLE_AND_CONTENT',
                  'detected': true,
                },
              ];
            case 'requestListenerRebind':
            case 'clearHistory':
            case 'openNotificationAccessSettings':
            case 'openAppDetailsSettings':
            case 'openBatterySettings':
            case 'openBatteryOptimizationSettings':
              return null;
            case 'testSpeech':
              return 'María López te envió 20 soles con 50 céntimos por Yape.';
            case 'updateSettings':
            case 'updateAppSelection':
            case 'startContinuousBackground':
            case 'stopContinuousBackground':
              return call.arguments;
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

  testWidgets('renders dashboard controls and history', (tester) async {
    await tester.pumpWidget(const YapeNotifierApp());
    await tester.pumpAndSettle();

    expect(find.text('VoxNotify'), findsOneWidget);
    expect(find.text('Acceso habilitado'), findsOneWidget);
    expect(find.text('Estado de VoxNotify'), findsOneWidget);
    expect(find.text('Voz activa'), findsOneWidget);
    expect(find.text('Modo de lectura de Yape'), findsOneWidget);
    expect(find.text('Aplicaciones para leer'), findsOneWidget);
    expect(find.text('Yape'), findsWidgets);
    expect(find.text('Aplicaciones detectadas'), findsOneWidget);
    await tester.drag(find.byType(Scrollable), const Offset(0, -500));
    await tester.pumpAndSettle();
    expect(find.text('VICTOR MANUEL MENESES'), findsOneWidget);
    expect(find.text('S/ 20.00'), findsOneWidget);
    await tester.drag(find.byType(Scrollable), const Offset(0, -600));
    await tester.pumpAndSettle();
    expect(find.textContaining('Privacidad:'), findsOneWidget);
  });
}
