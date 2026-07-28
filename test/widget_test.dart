import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:yape_notifier/main.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('yape_notifier/native');

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          switch (call.method) {
            case 'isNotificationAccessEnabled':
              return true;
            case 'getSettings':
              return {'voiceEnabled': true, 'fullPhrase': true};
            case 'getHistory':
              return [
                {
                  'timestamp': 1,
                  'amount': '20.00',
                  'spokenText': 'Yape recibido. 20 soles.',
                  'source': 'debug',
                },
              ];
            case 'requestListenerRebind':
            case 'clearHistory':
            case 'openNotificationAccessSettings':
              return null;
            case 'testSpeech':
              return 'Yape recibido. 20 soles con 50 céntimos.';
            case 'updateSettings':
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
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  testWidgets('renders dashboard controls and history', (tester) async {
    await tester.pumpWidget(const YapeNotifierApp());
    await tester.pumpAndSettle();

    expect(find.text('Yape Notifier'), findsOneWidget);
    expect(find.text('Acceso habilitado'), findsOneWidget);
    expect(find.text('Voz activa'), findsOneWidget);
    expect(find.text('Decir frase completa'), findsOneWidget);
    expect(find.text('S/ 20.00'), findsOneWidget);
    await tester.drag(find.byType(Scrollable), const Offset(0, -600));
    await tester.pumpAndSettle();
    expect(find.textContaining('Privacidad:'), findsOneWidget);
  });
}
