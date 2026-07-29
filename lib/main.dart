import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() {
  runApp(const YapeNotifierApp());
}

class YapeNotifierApp extends StatelessWidget {
  const YapeNotifierApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: 'VoxNotify',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF16A085),
          brightness: Brightness.light,
        ),
        useMaterial3: true,
      ),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> with WidgetsBindingObserver {
  static const _channel = MethodChannel('yape_notifier/native');
  bool _notificationAccess = false;
  bool _voiceEnabled = true;
  bool _fullPhrase = true;
  bool _loading = true;
  String? _debugResult;
  Map<String, String> _diagnostics = {};
  List<PaymentRecord> _history = [];
  List<ReadableApp> _apps = [];

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refresh();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _refresh();
      _channel.invokeMethod<void>('requestListenerRebind');
    }
  }

  Future<void> _refresh() async {
    setState(() => _loading = true);
    final access =
        await _channel.invokeMethod<bool>('isNotificationAccessEnabled') ??
        false;
    final settings = Map<String, Object?>.from(
      await _channel.invokeMethod<Map<dynamic, dynamic>>('getSettings') ?? {},
    );
    final history = await _loadHistory();
    final apps = await _loadApps();
    final diagnostics = await _loadDiagnostics();
    if (!mounted) return;
    setState(() {
      _notificationAccess = access;
      _voiceEnabled = settings['voiceEnabled'] as bool? ?? true;
      _fullPhrase = settings['fullPhrase'] as bool? ?? true;
      _history = history;
      _apps = apps;
      _diagnostics = diagnostics;
      _loading = false;
    });
  }

  Future<List<PaymentRecord>> _loadHistory() async {
    final raw = await _channel.invokeMethod<List<dynamic>>('getHistory') ?? [];
    return raw
        .map(
          (item) =>
              PaymentRecord.fromMap(Map<String, Object?>.from(item as Map)),
        )
        .toList();
  }

  Future<List<ReadableApp>> _loadApps() async {
    final raw =
        await _channel.invokeMethod<List<dynamic>>('getAvailableApps') ?? [];
    return raw
        .map(
          (item) => ReadableApp.fromMap(Map<String, Object?>.from(item as Map)),
        )
        .toList();
  }

  Future<Map<String, String>> _loadDiagnostics() async {
    final raw =
        await _channel.invokeMethod<Map<dynamic, dynamic>>(
          'getListenerDiagnostics',
        ) ??
        {};
    return raw.map((key, value) => MapEntry('$key', '$value'));
  }

  Future<void> _updateSettings({bool? voiceEnabled, bool? fullPhrase}) async {
    final updated = Map<String, Object?>.from(
      await _channel.invokeMethod<Map<dynamic, dynamic>>('updateSettings', {
            'voiceEnabled': voiceEnabled ?? _voiceEnabled,
            'fullPhrase': fullPhrase ?? _fullPhrase,
          }) ??
          {},
    );
    if (!mounted) return;
    setState(() {
      _voiceEnabled = updated['voiceEnabled'] as bool? ?? _voiceEnabled;
      _fullPhrase = updated['fullPhrase'] as bool? ?? _fullPhrase;
    });
  }

  Future<void> _openSettings() async {
    await _channel.invokeMethod<void>('openNotificationAccessSettings');
  }

  Future<void> _testSpeech() async {
    final phrase = await _channel.invokeMethod<String>('testSpeech');
    if (!mounted || phrase == null) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(phrase)));
  }

  Future<void> _clearHistory() async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Eliminar historial'),
        content: const Text('¿Deseas eliminar todo el historial?'),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(context).pop(false),
            child: const Text('Cancelar'),
          ),
          FilledButton(
            onPressed: () => Navigator.of(context).pop(true),
            child: const Text('Eliminar'),
          ),
        ],
      ),
    );
    if (confirmed != true) return;
    await _channel.invokeMethod<void>('clearHistory');
    await _refresh();
  }

  Future<void> _updateApp(
    ReadableApp app, {
    bool? enabled,
    String? readMode,
  }) async {
    await _channel.invokeMethod<Map<dynamic, dynamic>>('updateAppSelection', {
      'packageName': app.packageName,
      'label': app.label,
      'enabled': enabled ?? app.enabled,
      'readMode': readMode ?? app.readMode,
    });
    await _refresh();
  }

  Future<void> _runDebugPayload(String text) async {
    final result = Map<String, Object?>.from(
      await _channel.invokeMethod<Map<dynamic, dynamic>>('runDebugPayload', {
            'text': text,
          }) ??
          {},
    );
    if (!mounted) return;
    setState(
      () => _debugResult = result.entries
          .map((entry) => '${entry.key}: ${entry.value}')
          .join(' | '),
    );
    await _refresh();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('VoxNotify')),
      body: RefreshIndicator(
        onRefresh: _refresh,
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            _StatusPanel(
              access: _notificationAccess,
              loading: _loading,
              onOpenSettings: _openSettings,
            ),
            const SizedBox(height: 12),
            _SettingsPanel(
              voiceEnabled: _voiceEnabled,
              fullPhrase: _fullPhrase,
              onVoiceChanged: (value) => _updateSettings(voiceEnabled: value),
              onPhraseChanged: (value) => _updateSettings(fullPhrase: value),
              onTestSpeech: _testSpeech,
            ),
            const SizedBox(height: 12),
            _AppsPanel(apps: _apps, onChanged: _updateApp),
            const SizedBox(height: 12),
            _HistoryPanel(records: _history, onClear: _clearHistory),
            if (kDebugMode) ...[
              const SizedBox(height: 12),
              _DebugTools(
                result: _debugResult,
                diagnostics: _diagnostics,
                onRun: _runDebugPayload,
              ),
            ],
            const SizedBox(height: 12),
            const _PrivacyNotice(),
          ],
        ),
      ),
    );
  }
}

class _AppsPanel extends StatelessWidget {
  const _AppsPanel({required this.apps, required this.onChanged});

  final List<ReadableApp> apps;
  final Future<void> Function(
    ReadableApp app, {
    bool? enabled,
    String? readMode,
  })
  onChanged;

  @override
  Widget build(BuildContext context) {
    final enabled = apps.where((app) => app.enabled).toList();
    final detected = apps.where((app) => !app.enabled && app.detected).toList();
    final visible = apps.where((app) => !app.enabled && !app.detected).toList();
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.apps),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    'Aplicaciones para leer',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 8),
            const Text(
              'Activa solo las aplicaciones que quieras escuchar. Las demás se descartan inmediatamente.',
            ),
            const SizedBox(height: 12),
            if (enabled.isNotEmpty) ...[
              _AppSectionTitle('Aplicaciones activadas'),
              ...enabled.map((app) => _AppTile(app: app, onChanged: onChanged)),
            ],
            if (detected.isNotEmpty) ...[
              _AppSectionTitle('Aplicaciones detectadas'),
              ...detected.map(
                (app) => _AppTile(app: app, onChanged: onChanged),
              ),
            ],
            if (visible.isNotEmpty) ...[
              _AppSectionTitle('Aplicaciones visibles'),
              ...visible.map((app) => _AppTile(app: app, onChanged: onChanged)),
            ],
          ],
        ),
      ),
    );
  }
}

class _AppSectionTitle extends StatelessWidget {
  const _AppSectionTitle(this.text);

  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: Text(text, style: Theme.of(context).textTheme.labelLarge),
    );
  }
}

class _AppTile extends StatelessWidget {
  const _AppTile({required this.app, required this.onChanged});

  final ReadableApp app;
  final Future<void> Function(
    ReadableApp app, {
    bool? enabled,
    String? readMode,
  })
  onChanged;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        SwitchListTile(
          value: app.enabled,
          onChanged: (value) => onChanged(app, enabled: value),
          title: Text(app.label),
          subtitle: Text(app.packageName),
          secondary: Icon(
            app.readMode == 'SMART_YAPE'
                ? Icons.auto_awesome
                : Icons.notifications,
          ),
        ),
        if (app.enabled && app.readMode != 'SMART_YAPE')
          Padding(
            padding: const EdgeInsets.only(left: 16, right: 16, bottom: 8),
            child: SegmentedButton<String>(
              segments: const [
                ButtonSegment(
                  value: 'TITLE_AND_CONTENT',
                  label: Text('Título y mensaje'),
                ),
                ButtonSegment(value: 'TITLE_ONLY', label: Text('Solo título')),
                ButtonSegment(value: 'SENDER_ONLY', label: Text('Remitente')),
              ],
              selected: {app.readMode},
              onSelectionChanged: (selection) =>
                  onChanged(app, readMode: selection.first),
            ),
          ),
      ],
    );
  }
}

class _StatusPanel extends StatelessWidget {
  const _StatusPanel({
    required this.access,
    required this.loading,
    required this.onOpenSettings,
  });

  final bool access;
  final bool loading;
  final VoidCallback onOpenSettings;

  @override
  Widget build(BuildContext context) {
    final color = access ? Colors.green.shade700 : Colors.red.shade700;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                Icon(
                  access ? Icons.verified : Icons.notifications_off,
                  color: color,
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    loading
                        ? 'Comprobando acceso...'
                        : access
                        ? 'Acceso habilitado'
                        : 'Acceso pendiente',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            FilledButton.icon(
              onPressed: onOpenSettings,
              icon: const Icon(Icons.settings),
              label: const Text('Habilitar acceso a notificaciones'),
            ),
          ],
        ),
      ),
    );
  }
}

class _SettingsPanel extends StatelessWidget {
  const _SettingsPanel({
    required this.voiceEnabled,
    required this.fullPhrase,
    required this.onVoiceChanged,
    required this.onPhraseChanged,
    required this.onTestSpeech,
  });

  final bool voiceEnabled;
  final bool fullPhrase;
  final ValueChanged<bool> onVoiceChanged;
  final ValueChanged<bool> onPhraseChanged;
  final VoidCallback onTestSpeech;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Column(
        children: [
          SwitchListTile(
            value: voiceEnabled,
            onChanged: onVoiceChanged,
            title: const Text('Voz activa'),
            secondary: const Icon(Icons.volume_up),
          ),
          SwitchListTile(
            value: fullPhrase,
            onChanged: onPhraseChanged,
            title: const Text('Modo de lectura de Yape'),
            subtitle: Text(
              fullPhrase ? 'Decir nombre y monto' : 'Decir solamente el monto',
            ),
            secondary: const Icon(Icons.record_voice_over),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
            child: SizedBox(
              width: double.infinity,
              child: OutlinedButton.icon(
                onPressed: onTestSpeech,
                icon: const Icon(Icons.play_arrow),
                label: const Text('Probar voz'),
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _HistoryPanel extends StatelessWidget {
  const _HistoryPanel({required this.records, required this.onClear});

  final List<PaymentRecord> records;
  final VoidCallback onClear;

  @override
  Widget build(BuildContext context) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                const Icon(Icons.history),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    'Historial',
                    style: Theme.of(context).textTheme.titleMedium,
                  ),
                ),
                IconButton(
                  tooltip: 'Borrar historial',
                  onPressed: records.isEmpty ? null : onClear,
                  icon: const Icon(Icons.delete_outline),
                ),
              ],
            ),
            if (records.isEmpty)
              const Padding(
                padding: EdgeInsets.only(top: 12),
                child: Text('Aún no hay pagos detectados.'),
              )
            else
              ...records.map(
                (record) => ListTile(
                  contentPadding: EdgeInsets.zero,
                  leading: Icon(
                    record.amount.isEmpty
                        ? Icons.notifications_active
                        : Icons.payments,
                  ),
                  title: Text(
                    record.amount.isEmpty
                        ? record.source
                        : record.sender?.isNotEmpty == true
                        ? record.sender!
                        : 'Yape',
                  ),
                  subtitle: Text(
                    record.amount.isEmpty
                        ? record.spokenText
                        : 'S/ ${record.amount}\n${record.spokenText}',
                  ),
                  isThreeLine: record.amount.isNotEmpty,
                  trailing: Text(
                    record.amount.isEmpty
                        ? 'voz'
                        : !record.announced
                        ? 'silencio'
                        : record.source == 'debug'
                        ? 'debug'
                        : 'Yape',
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class _DebugTools extends StatelessWidget {
  const _DebugTools({
    required this.result,
    required this.diagnostics,
    required this.onRun,
  });

  final String? result;
  final Map<String, String> diagnostics;
  final ValueChanged<String> onRun;

  @override
  Widget build(BuildContext context) {
    const samples = [
      'Yape! VICTOR MANUEL MENESES te envió un pago por S/ 1',
      'Yape! María López te envió un pago por S/ 25.50',
      'Carlos te yapeó S/ 15',
      'Pago enviado: Enviaste S/ 30',
      'Promoción: gana S/ 100',
    ];
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Herramientas de prueba',
              style: Theme.of(context).textTheme.titleMedium,
            ),
            const SizedBox(height: 6),
            const Text(
              'Ejecutan el procesador local; no validan el acceso real a notificaciones del sistema.',
            ),
            const SizedBox(height: 12),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: samples
                  .map(
                    (sample) => OutlinedButton(
                      onPressed: () => onRun(sample),
                      child: Text(sample),
                    ),
                  )
                  .toList(),
            ),
            if (result != null) ...[const SizedBox(height: 12), Text(result!)],
            if (diagnostics.isNotEmpty) ...[
              const SizedBox(height: 12),
              Text(
                diagnostics.entries
                    .where((entry) => entry.value.isNotEmpty)
                    .map((entry) => '${entry.key}: ${entry.value}')
                    .join('\n'),
              ),
            ],
          ],
        ),
      ),
    );
  }
}

class _PrivacyNotice extends StatelessWidget {
  const _PrivacyNotice();

  @override
  Widget build(BuildContext context) {
    return const Card(
      child: Padding(
        padding: EdgeInsets.all(16),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Icon(Icons.lock_outline),
            SizedBox(width: 10),
            Expanded(
              child: Text(
                'Privacidad: las notificaciones se procesan localmente en este teléfono. No se usa Firebase, servidores, analíticas ni internet.',
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class PaymentRecord {
  const PaymentRecord({
    required this.timestamp,
    required this.amount,
    required this.sender,
    required this.spokenText,
    required this.source,
    required this.announced,
  });

  factory PaymentRecord.fromMap(Map<String, Object?> map) => PaymentRecord(
    timestamp: map['timestamp'] as int? ?? 0,
    amount: map['amount'] as String? ?? '',
    sender: map['sender'] as String?,
    spokenText: map['spokenText'] as String? ?? '',
    source: map['source'] as String? ?? '',
    announced: map['announced'] as bool? ?? true,
  );

  final int timestamp;
  final String amount;
  final String? sender;
  final String spokenText;
  final String source;
  final bool announced;
}

class ReadableApp {
  const ReadableApp({
    required this.packageName,
    required this.label,
    required this.enabled,
    required this.readMode,
    required this.detected,
  });

  factory ReadableApp.fromMap(Map<String, Object?> map) => ReadableApp(
    packageName: map['packageName'] as String? ?? '',
    label: map['label'] as String? ?? '',
    enabled: map['enabled'] as bool? ?? false,
    readMode: map['readMode'] as String? ?? 'TITLE_AND_CONTENT',
    detected: map['detected'] as bool? ?? false,
  );

  final String packageName;
  final String label;
  final bool enabled;
  final String readMode;
  final bool detected;
}
