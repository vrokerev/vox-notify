import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart' show ScrollCacheExtent;
import 'package:flutter/services.dart';

void main() {
  runApp(const VoxNotifyApp());
}

class VoxNotifyApp extends StatelessWidget {
  const VoxNotifyApp({super.key});

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
  static const _events = EventChannel('voxnotify/events');

  bool _notificationAccess = false;
  bool _voiceEnabled = true;
  bool _fullPhrase = true;
  bool _continuousBackground = false;
  bool _continuousBackgroundRunning = false;
  bool _postNotificationsGranted = false;
  bool _batteryOptimizationIgnored = false;
  bool _loading = true;

  bool _appsLoading = false;
  bool _appsLoaded = false;
  bool _appsDirty = true;

  String? _debugResult;
  Map<String, String> _diagnostics = {};
  List<PaymentRecord> _history = [];
  List<ReadableApp> _apps = [];

  StreamSubscription<dynamic>? _eventSubscription;
  Timer? _runtimeRefreshDebounce;
  bool _runtimeRefreshInFlight = false;
  bool _runtimeRefreshQueued = false;
  bool _runtimeRefreshQueuedNeedsHistory = false;
  bool _debouncedNeedsHistory = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _eventSubscription = _events.receiveBroadcastStream().listen(_onNativeEvent);
    _refreshFull();
  }

  @override
  void dispose() {
    _runtimeRefreshDebounce?.cancel();
    _eventSubscription?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      _appsDirty = true;
      _refreshFull(silent: true);
      _channel.invokeMethod<void>('requestListenerRebind');
    }
  }

  void _onNativeEvent(dynamic event) {
    String type = '';
    if (event is Map) {
      type = '${event['type'] ?? ''}';
    }

    if (type == 'app_detected' || type == 'app_selection_changed') {
      _appsDirty = true;
    }

    final includeHistory =
        type == 'notification_processed' ||
        type == 'tts_queued' ||
        type == 'listener_probe_received';

    _debouncedNeedsHistory = _debouncedNeedsHistory || includeHistory;
    _runtimeRefreshDebounce?.cancel();
    _runtimeRefreshDebounce = Timer(
      const Duration(milliseconds: 900),
      () {
        final needsHistory = _debouncedNeedsHistory;
        _debouncedNeedsHistory = false;
        _refreshRuntime(includeHistory: needsHistory);
      },
    );
  }

  Future<void> _refreshFull({bool silent = false}) async {
    if (!silent && mounted) {
      setState(() => _loading = true);
    }

    try {
      final results = await Future.wait<dynamic>([
        _channel.invokeMethod<bool>('isNotificationAccessEnabled'),
        _channel.invokeMethod<Map<dynamic, dynamic>>('getSettings'),
        _channel.invokeMethod<List<dynamic>>('getHistory'),
        _channel.invokeMethod<Map<dynamic, dynamic>>('getListenerDiagnostics'),
        _channel.invokeMethod<bool>('isContinuousBackgroundRunning'),
        _channel.invokeMethod<bool>('hasPostNotificationsPermission'),
        _channel.invokeMethod<bool>('getBatteryOptimizationIgnored'),
      ]);

      if (!mounted) return;

      final settings = Map<String, Object?>.from(
        (results[1] as Map<dynamic, dynamic>?) ?? const {},
      );
      final history = _historyFromRaw(results[2] as List<dynamic>?);
      final diagnostics = _diagnosticsFromRaw(
        results[3] as Map<dynamic, dynamic>?,
      );

      setState(() {
        _notificationAccess = results[0] as bool? ?? false;
        _voiceEnabled = settings['voiceEnabled'] as bool? ?? true;
        _fullPhrase = settings['fullPhrase'] as bool? ?? true;
        _continuousBackground =
            settings['continuousBackground'] as bool? ?? false;
        _history = history;
        _diagnostics = diagnostics;
        _continuousBackgroundRunning = results[4] as bool? ?? false;
        _postNotificationsGranted = results[5] as bool? ?? false;
        _batteryOptimizationIgnored = results[6] as bool? ?? false;
        _loading = false;
      });
      if (_appsLoaded && _appsDirty) {
        unawaited(_loadAppsIfNeeded(force: true));
      }
    } on PlatformException {
      if (!mounted) return;
      setState(() => _loading = false);
    }
  }

  Future<void> _refreshRuntime({bool includeHistory = false}) async {
    if (_runtimeRefreshInFlight) {
      _runtimeRefreshQueued = true;
      _runtimeRefreshQueuedNeedsHistory =
          _runtimeRefreshQueuedNeedsHistory || includeHistory;
      return;
    }

    _runtimeRefreshInFlight = true;
    try {
      final futures = <Future<dynamic>>[
        _channel.invokeMethod<Map<dynamic, dynamic>>('getListenerDiagnostics'),
        _channel.invokeMethod<bool>('isContinuousBackgroundRunning'),
      ];
      if (includeHistory) {
        futures.add(_channel.invokeMethod<List<dynamic>>('getHistory'));
      }

      final results = await Future.wait<dynamic>(futures);
      if (!mounted) return;

      final diagnostics = _diagnosticsFromRaw(
        results[0] as Map<dynamic, dynamic>?,
      );
      final backgroundRunning = results[1] as bool? ?? false;
      final history = includeHistory
          ? _historyFromRaw(results[2] as List<dynamic>?)
          : null;

      final diagnosticsChanged = !mapEquals(_diagnostics, diagnostics);
      final backgroundChanged =
          _continuousBackgroundRunning != backgroundRunning;
      final historyChanged = history != null && !_sameHistory(_history, history);

      if (diagnosticsChanged || backgroundChanged || historyChanged) {
        setState(() {
          _diagnostics = diagnostics;
          _continuousBackgroundRunning = backgroundRunning;
          if (history != null) _history = history;
        });
      }
    } on PlatformException {
      // Mantener la UI estable si Android está ocupado; el siguiente evento
      // volverá a sincronizar el estado.
    } finally {
      _runtimeRefreshInFlight = false;
      if (_runtimeRefreshQueued) {
        final queuedNeedsHistory = _runtimeRefreshQueuedNeedsHistory;
        _runtimeRefreshQueued = false;
        _runtimeRefreshQueuedNeedsHistory = false;
        unawaited(_refreshRuntime(includeHistory: queuedNeedsHistory));
      }
    }
  }

  List<PaymentRecord> _historyFromRaw(List<dynamic>? raw) {
    return (raw ?? const [])
        .map(
          (item) =>
              PaymentRecord.fromMap(Map<String, Object?>.from(item as Map)),
        )
        .toList(growable: false);
  }

  Map<String, String> _diagnosticsFromRaw(Map<dynamic, dynamic>? raw) {
    return (raw ?? const {}).map(
      (key, value) => MapEntry('$key', '$value'),
    );
  }

  bool _sameHistory(List<PaymentRecord> a, List<PaymentRecord> b) {
    if (a.length != b.length) return false;
    if (a.isEmpty) return true;
    final firstA = a.first;
    final firstB = b.first;
    return firstA.timestamp == firstB.timestamp &&
        firstA.spokenText == firstB.spokenText &&
        firstA.amount == firstB.amount;
  }

  Future<void> _loadAppsIfNeeded({bool force = false}) async {
    if (_appsLoading) return;
    if (!force && _appsLoaded && !_appsDirty) return;

    setState(() => _appsLoading = true);
    try {
      final raw =
          await _channel.invokeMethod<List<dynamic>>('getAvailableApps') ?? [];
      final apps = raw
          .map(
            (item) =>
                ReadableApp.fromMap(Map<String, Object?>.from(item as Map)),
          )
          .toList(growable: false);
      if (!mounted) return;
      setState(() {
        _apps = apps;
        _appsLoaded = true;
        _appsDirty = false;
        _appsLoading = false;
      });
    } on PlatformException {
      if (!mounted) return;
      setState(() => _appsLoading = false);
    }
  }

  Future<void> _updateSettings({bool? voiceEnabled, bool? fullPhrase}) async {
    final updated = Map<String, Object?>.from(
      await _channel.invokeMethod<Map<dynamic, dynamic>>('updateSettings', {
            'voiceEnabled': voiceEnabled ?? _voiceEnabled,
            'fullPhrase': fullPhrase ?? _fullPhrase,
            'continuousBackground': _continuousBackground,
          }) ??
          {},
    );
    if (!mounted) return;
    setState(() {
      _voiceEnabled = updated['voiceEnabled'] as bool? ?? _voiceEnabled;
      _fullPhrase = updated['fullPhrase'] as bool? ?? _fullPhrase;
      _continuousBackground =
          updated['continuousBackground'] as bool? ?? _continuousBackground;
      _continuousBackgroundRunning =
          updated['continuousBackgroundRunning'] as bool? ??
          _continuousBackgroundRunning;
      _postNotificationsGranted =
          updated['postNotificationsPermissionGranted'] as bool? ??
          _postNotificationsGranted;
    });
  }

  Future<void> _setContinuousBackground(bool enabled) async {
    if (enabled) {
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: const Text('Lectura continua'),
          content: const Text(
            'VoxNotify mostrará una notificación persistente para mejorar la lectura en segundo plano.',
          ),
          actions: [
            TextButton(
              onPressed: () => Navigator.of(context).pop(false),
              child: const Text('Cancelar'),
            ),
            FilledButton(
              onPressed: () => Navigator.of(context).pop(true),
              child: const Text('Activar'),
            ),
          ],
        ),
      );
      if (confirmed != true) return;
      final granted =
          await _channel.invokeMethod<bool>(
            'requestPostNotificationsPermission',
          ) ??
          false;
      if (mounted) {
        setState(() => _postNotificationsGranted = granted);
      }
    }

    final updated = Map<String, Object?>.from(
      await _channel.invokeMethod<Map<dynamic, dynamic>>(
            enabled ? 'startContinuousBackground' : 'stopContinuousBackground',
          ) ??
          {},
    );
    if (!mounted) return;
    setState(() {
      _continuousBackground =
          updated['continuousBackground'] as bool? ?? enabled;
      _continuousBackgroundRunning =
          updated['continuousBackgroundRunning'] as bool? ??
          _continuousBackgroundRunning;
      _postNotificationsGranted =
          updated['postNotificationsPermissionGranted'] as bool? ??
          _postNotificationsGranted;
    });
    await _refreshRuntime();
  }

  Future<void> _requestListenerRebind() async {
    await _channel.invokeMethod<void>('requestListenerRebind');
    await Future<void>.delayed(const Duration(milliseconds: 500));
    await _refreshRuntime();
  }

  Future<void> _openAppDetails() async {
    await _channel.invokeMethod<void>('openAppDetailsSettings');
  }

  Future<void> _openBatterySettings() async {
    await _channel.invokeMethod<void>('openBatterySettings');
  }

  Future<void> _openBatteryOptimizationSettings() async {
    await _channel.invokeMethod<void>('openBatteryOptimizationSettings');
  }

  Future<void> _openXiaomiAutostartSettings() async {
    await _channel.invokeMethod<void>('openXiaomiAutostartSettings');
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
    if (!mounted) return;
    setState(() => _history = const []);
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
    _appsDirty = true;
    await _loadAppsIfNeeded(force: true);
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
    await _refreshRuntime(includeHistory: true);
  }

  Future<void> _runListenerProbe() async {
    final result = Map<String, Object?>.from(
      await _channel.invokeMethod<Map<dynamic, dynamic>>('runListenerProbe') ??
          {},
    );
    if (!mounted) return;
    setState(
      () => _debugResult = result.entries
          .map((entry) => '${entry.key}: ${entry.value}')
          .join(' | '),
    );
    await Future<void>.delayed(const Duration(seconds: 2));
    await _refreshRuntime();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('VoxNotify')),
      body: RefreshIndicator(
        onRefresh: () => _refreshFull(silent: true),
        child: ListView(
          physics: const AlwaysScrollableScrollPhysics(
            parent: BouncingScrollPhysics(),
          ),
          scrollCacheExtent: const ScrollCacheExtent.pixels(500.0),
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 24),
          children: [
            RepaintBoundary(
              child: _StatusPanel(
                access: _notificationAccess,
                loading: _loading,
                diagnostics: _diagnostics,
                voiceEnabled: _voiceEnabled,
                continuousBackground: _continuousBackground,
                continuousBackgroundRunning: _continuousBackgroundRunning,
                postNotificationsGranted: _postNotificationsGranted,
                batteryOptimizationIgnored: _batteryOptimizationIgnored,
                onOpenSettings: _openSettings,
                onContinuousChanged: _setContinuousBackground,
                onEnableVoice: () => _updateSettings(voiceEnabled: true),
                onRequestListenerRebind: _requestListenerRebind,
                onOpenAppDetails: _openAppDetails,
                onOpenBatterySettings: _openBatterySettings,
                onOpenBatteryOptimizationSettings:
                    _openBatteryOptimizationSettings,
                onOpenXiaomiAutostartSettings: _openXiaomiAutostartSettings,
              ),
            ),
            const SizedBox(height: 12),
            RepaintBoundary(
              child: _SettingsPanel(
                voiceEnabled: _voiceEnabled,
                fullPhrase: _fullPhrase,
                onVoiceChanged: (value) => _updateSettings(voiceEnabled: value),
                onPhraseChanged: (value) => _updateSettings(fullPhrase: value),
                onTestSpeech: _testSpeech,
              ),
            ),
            const SizedBox(height: 12),
            RepaintBoundary(
              child: _AppsPanel(
                apps: _apps,
                loading: _appsLoading,
                loaded: _appsLoaded,
                onExpand: () => _loadAppsIfNeeded(),
                onChanged: _updateApp,
              ),
            ),
            const SizedBox(height: 12),
            RepaintBoundary(
              child: _HistoryPanel(records: _history, onClear: _clearHistory),
            ),
            if (kDebugMode) ...[
              const SizedBox(height: 12),
              RepaintBoundary(
                child: _DebugTools(
                  result: _debugResult,
                  diagnostics: _diagnostics,
                  onRun: _runDebugPayload,
                  onListenerProbe: _runListenerProbe,
                ),
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

class _StatusPanel extends StatelessWidget {
  const _StatusPanel({
    required this.access,
    required this.loading,
    required this.diagnostics,
    required this.voiceEnabled,
    required this.continuousBackground,
    required this.continuousBackgroundRunning,
    required this.postNotificationsGranted,
    required this.batteryOptimizationIgnored,
    required this.onOpenSettings,
    required this.onContinuousChanged,
    required this.onEnableVoice,
    required this.onRequestListenerRebind,
    required this.onOpenAppDetails,
    required this.onOpenBatterySettings,
    required this.onOpenBatteryOptimizationSettings,
    required this.onOpenXiaomiAutostartSettings,
  });

  final bool access;
  final bool loading;
  final Map<String, String> diagnostics;
  final bool voiceEnabled;
  final bool continuousBackground;
  final bool continuousBackgroundRunning;
  final bool postNotificationsGranted;
  final bool batteryOptimizationIgnored;
  final VoidCallback onOpenSettings;
  final ValueChanged<bool> onContinuousChanged;
  final VoidCallback onEnableVoice;
  final VoidCallback onRequestListenerRebind;
  final VoidCallback onOpenAppDetails;
  final VoidCallback onOpenBatterySettings;
  final VoidCallback onOpenBatteryOptimizationSettings;
  final VoidCallback onOpenXiaomiAutostartSettings;

  @override
  Widget build(BuildContext context) {
    final listenerLive = diagnostics['listenerLive'] == 'true';
    final foregroundLive =
        diagnostics['foregroundSpeechServiceActuallyRunning'] == 'true';
    final manufacturer = diagnostics['manufacturer']?.toLowerCase() ?? '';
    final isXiaomi =
        manufacturer.contains('xiaomi') ||
        manufacturer.contains('redmi') ||
        manufacturer.contains('poco');

    final issues = <_HealthIssue>[];

    if (!loading && !access) {
      issues.add(
        _HealthIssue(
          severity: _IssueSeverity.critical,
          icon: Icons.notifications_off,
          title: 'Acceso a notificaciones deshabilitado',
          message: 'VoxNotify no puede detectar los pagos de Yape.',
          actionLabel: 'Habilitar acceso',
          onAction: onOpenSettings,
        ),
      );
    }

    if (!loading && access && !listenerLive) {
      issues.add(
        _HealthIssue(
          severity: _IssueSeverity.critical,
          icon: Icons.sensors_off,
          title: 'El lector de notificaciones no responde',
          message: 'Intentaremos reconectarlo sin reiniciar la app.',
          actionLabel: 'Reconectar',
          onAction: onRequestListenerRebind,
        ),
      );
    }

    if (!loading && !voiceEnabled) {
      issues.add(
        _HealthIssue(
          severity: _IssueSeverity.warning,
          icon: Icons.volume_off,
          title: 'La voz está desactivada',
          message: 'Los pagos pueden detectarse, pero no se leerán en voz alta.',
          actionLabel: 'Activar voz',
          onAction: onEnableVoice,
        ),
      );
    }

    if (!loading && !continuousBackground) {
      issues.add(
        _HealthIssue(
          severity: _IssueSeverity.warning,
          icon: Icons.sync_disabled,
          title: 'Lectura continua desactivada',
          message: 'Puede reducir la fiabilidad cuando la app lleva tiempo cerrada.',
          actionLabel: 'Activar',
          onAction: () => onContinuousChanged(true),
        ),
      );
    } else if (!loading && !(foregroundLive || continuousBackgroundRunning)) {
      issues.add(
        _HealthIssue(
          severity: _IssueSeverity.critical,
          icon: Icons.error_outline,
          title: 'Servicio en segundo plano detenido',
          message: 'La lectura continua está activada, pero Android no mantiene el servicio corriendo.',
          actionLabel: 'Reactivar',
          onAction: () => onContinuousChanged(true),
        ),
      );
    }

    if (!loading && continuousBackground && !postNotificationsGranted) {
      issues.add(
        _HealthIssue(
          severity: _IssueSeverity.warning,
          icon: Icons.notifications_paused,
          title: 'Notificación persistente bloqueada',
          message: 'Android puede ocultar o limitar el servicio de VoxNotify.',
          actionLabel: 'Abrir ajustes',
          onAction: onOpenAppDetails,
        ),
      );
    }

    if (!loading && !batteryOptimizationIgnored) {
      issues.add(
        _HealthIssue(
          severity: _IssueSeverity.warning,
          icon: Icons.battery_saver,
          title: 'Android puede restringir VoxNotify',
          message: 'Conviene configurar la batería en “Sin restricciones”.',
          actionLabel: 'Configurar',
          onAction: onOpenBatteryOptimizationSettings,
        ),
      );
    }

    final hasCritical = issues.any(
      (issue) => issue.severity == _IssueSeverity.critical,
    );

    return Card(
      clipBehavior: Clip.antiAlias,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 16, 16, 12),
            child: Row(
              children: [
                Icon(
                  loading
                      ? Icons.hourglass_top
                      : hasCritical
                      ? Icons.error
                      : issues.isEmpty
                      ? Icons.check_circle
                      : Icons.info,
                  color: loading
                      ? Theme.of(context).colorScheme.onSurfaceVariant
                      : hasCritical
                      ? Theme.of(context).colorScheme.error
                      : issues.isEmpty
                      ? Colors.green.shade700
                      : Colors.orange.shade800,
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Estado de VoxNotify',
                        style: Theme.of(context).textTheme.titleMedium,
                      ),
                      const SizedBox(height: 2),
                      Text(
                        loading
                            ? 'Comprobando el teléfono…'
                            : hasCritical
                            ? 'Hay algo que debes corregir'
                            : issues.isEmpty
                            ? 'Todo listo para leer tus notificaciones'
                            : 'Funciona, pero hay recomendaciones',
                        style: Theme.of(context).textTheme.bodySmall,
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
          if (!loading && issues.isEmpty)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
              child: Container(
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(
                  color: Colors.green.withValues(alpha: 0.08),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: const Row(
                  children: [
                    Icon(Icons.check, size: 20),
                    SizedBox(width: 10),
                    Expanded(
                      child: Text('No se detectan problemas en este momento.'),
                    ),
                  ],
                ),
              ),
            ),
          if (!loading && issues.isNotEmpty)
            Padding(
              padding: const EdgeInsets.fromLTRB(12, 0, 12, 12),
              child: Column(
                children: [
                  for (final issue in issues) ...[
                    _IssueTile(issue: issue),
                    if (issue != issues.last) const SizedBox(height: 8),
                  ],
                ],
              ),
            ),
          _BackgroundOptions(
            continuousBackground: continuousBackground,
            continuousBackgroundRunning: continuousBackgroundRunning,
            isXiaomi: isXiaomi,
            onContinuousChanged: onContinuousChanged,
            onOpenAppDetails: onOpenAppDetails,
            onOpenBatterySettings: onOpenBatterySettings,
            onOpenBatteryOptimizationSettings: onOpenBatteryOptimizationSettings,
            onOpenXiaomiAutostartSettings: onOpenXiaomiAutostartSettings,
          ),
        ],
      ),
    );
  }
}

enum _IssueSeverity { critical, warning }

class _HealthIssue {
  const _HealthIssue({
    required this.severity,
    required this.icon,
    required this.title,
    required this.message,
    required this.actionLabel,
    required this.onAction,
  });

  final _IssueSeverity severity;
  final IconData icon;
  final String title;
  final String message;
  final String actionLabel;
  final VoidCallback onAction;
}

class _IssueTile extends StatelessWidget {
  const _IssueTile({required this.issue});

  final _HealthIssue issue;

  @override
  Widget build(BuildContext context) {
    final critical = issue.severity == _IssueSeverity.critical;
    final color = critical
        ? Theme.of(context).colorScheme.error
        : Colors.orange.shade900;

    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.08),
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: color.withValues(alpha: 0.25)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Icon(issue.icon, color: color, size: 22),
              const SizedBox(width: 10),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      issue.title,
                      style: Theme.of(context).textTheme.titleSmall?.copyWith(
                        color: color,
                        fontWeight: FontWeight.w700,
                      ),
                    ),
                    const SizedBox(height: 3),
                    Text(issue.message),
                  ],
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          Align(
            alignment: Alignment.centerRight,
            child: critical
                ? FilledButton.icon(
                    onPressed: issue.onAction,
                    icon: const Icon(Icons.arrow_forward, size: 18),
                    label: Text(issue.actionLabel),
                    style: FilledButton.styleFrom(backgroundColor: color),
                  )
                : OutlinedButton.icon(
                    onPressed: issue.onAction,
                    icon: const Icon(Icons.tune, size: 18),
                    label: Text(issue.actionLabel),
                  ),
          ),
        ],
      ),
    );
  }
}

class _BackgroundOptions extends StatefulWidget {
  const _BackgroundOptions({
    required this.continuousBackground,
    required this.continuousBackgroundRunning,
    required this.isXiaomi,
    required this.onContinuousChanged,
    required this.onOpenAppDetails,
    required this.onOpenBatterySettings,
    required this.onOpenBatteryOptimizationSettings,
    required this.onOpenXiaomiAutostartSettings,
  });

  final bool continuousBackground;
  final bool continuousBackgroundRunning;
  final bool isXiaomi;
  final ValueChanged<bool> onContinuousChanged;
  final VoidCallback onOpenAppDetails;
  final VoidCallback onOpenBatterySettings;
  final VoidCallback onOpenBatteryOptimizationSettings;
  final VoidCallback onOpenXiaomiAutostartSettings;

  @override
  State<_BackgroundOptions> createState() => _BackgroundOptionsState();
}

class _BackgroundOptionsState extends State<_BackgroundOptions> {
  bool _expanded = false;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        const Divider(height: 1),
        InkWell(
          onTap: () => setState(() => _expanded = !_expanded),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 13),
            child: Row(
              children: [
                const Icon(Icons.settings_suggest_outlined, size: 21),
                const SizedBox(width: 10),
                const Expanded(child: Text('Opciones de segundo plano')),
                AnimatedRotation(
                  turns: _expanded ? 0.5 : 0,
                  duration: const Duration(milliseconds: 180),
                  child: const Icon(Icons.expand_more),
                ),
              ],
            ),
          ),
        ),
        if (_expanded)
          Padding(
            padding: const EdgeInsets.fromLTRB(8, 0, 8, 12),
            child: Column(
              children: [
                SwitchListTile(
                  value: widget.continuousBackground,
                  onChanged: widget.onContinuousChanged,
                  title: const Text('Lectura continua'),
                  subtitle: Text(
                    widget.continuousBackgroundRunning
                        ? 'Servicio activo.'
                        : widget.continuousBackground
                        ? 'Configurada; Android está iniciando el servicio.'
                        : 'Mejora la fiabilidad con la app cerrada.',
                  ),
                  secondary: const Icon(Icons.sync),
                ),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: 8),
                  child: Wrap(
                    spacing: 8,
                    runSpacing: 8,
                    children: [
                      OutlinedButton.icon(
                        onPressed: widget.onOpenAppDetails,
                        icon: const Icon(Icons.app_settings_alt),
                        label: const Text('Detalles de la app'),
                      ),
                      OutlinedButton.icon(
                        onPressed: widget.onOpenBatterySettings,
                        icon: const Icon(Icons.battery_saver),
                        label: const Text('Batería'),
                      ),
                      OutlinedButton.icon(
                        onPressed: widget.onOpenBatteryOptimizationSettings,
                        icon: const Icon(Icons.power_settings_new),
                        label: const Text('Optimización'),
                      ),
                      if (widget.isXiaomi)
                        OutlinedButton.icon(
                          onPressed: widget.onOpenXiaomiAutostartSettings,
                          icon: const Icon(Icons.restart_alt),
                          label: const Text('Autoinicio'),
                        ),
                    ],
                  ),
                ),
                if (widget.isXiaomi)
                  const Padding(
                    padding: EdgeInsets.fromLTRB(12, 12, 12, 0),
                    child: Text(
                      'En Xiaomi/HyperOS conviene usar batería “Sin restricciones” y permitir inicio automático.',
                    ),
                  ),
              ],
            ),
          ),
      ],
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
            subtitle: const Text('Leer en voz alta las notificaciones elegidas.'),
            secondary: const Icon(Icons.volume_up),
          ),
          const Divider(height: 1),
          SwitchListTile(
            value: fullPhrase,
            onChanged: onPhraseChanged,
            title: const Text('Leer nombre y monto de Yape'),
            subtitle: Text(
              fullPhrase
                  ? 'Ejemplo: “María te envió 20 soles por Yape”.'
                  : 'Se dirá solamente el monto.',
            ),
            secondary: const Icon(Icons.record_voice_over),
          ),
          Padding(
            padding: const EdgeInsets.fromLTRB(16, 4, 16, 16),
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

class _AppsPanel extends StatefulWidget {
  const _AppsPanel({
    required this.apps,
    required this.loading,
    required this.loaded,
    required this.onExpand,
    required this.onChanged,
  });

  final List<ReadableApp> apps;
  final bool loading;
  final bool loaded;
  final Future<void> Function() onExpand;
  final Future<void> Function(
    ReadableApp app, {
    bool? enabled,
    String? readMode,
  }) onChanged;

  @override
  State<_AppsPanel> createState() => _AppsPanelState();
}

class _AppsPanelState extends State<_AppsPanel> {
  bool _expanded = false;
  bool _detectedExpanded = false;
  bool _visibleExpanded = false;

  Future<void> _toggle() async {
    final next = !_expanded;
    setState(() => _expanded = next);
    if (next) {
      await widget.onExpand();
    }
  }

  @override
  Widget build(BuildContext context) {
    final enabled = widget.apps.where((app) => app.enabled).toList();
    final detected = widget.apps
        .where((app) => !app.enabled && app.detected)
        .toList();
    final visible = widget.apps
        .where((app) => !app.enabled && !app.detected)
        .toList();

    return Card(
      clipBehavior: Clip.antiAlias,
      child: Column(
        children: [
          InkWell(
            onTap: _toggle,
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Row(
                children: [
                  const Icon(Icons.apps),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Aplicaciones para leer',
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                        const SizedBox(height: 2),
                        Text(
                          widget.loading
                              ? 'Cargando aplicaciones…'
                              : widget.loaded
                              ? '${enabled.length} activada${enabled.length == 1 ? '' : 's'}'
                              : 'Toca para ver y configurar',
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ],
                    ),
                  ),
                  AnimatedRotation(
                    turns: _expanded ? 0.5 : 0,
                    duration: const Duration(milliseconds: 180),
                    child: const Icon(Icons.expand_more),
                  ),
                ],
              ),
            ),
          ),
          if (_expanded) ...[
            const Divider(height: 1),
            if (widget.loading)
              const Padding(
                padding: EdgeInsets.all(24),
                child: Center(child: CircularProgressIndicator()),
              )
            else
              Padding(
                padding: const EdgeInsets.fromLTRB(8, 8, 8, 12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const Padding(
                      padding: EdgeInsets.fromLTRB(8, 4, 8, 8),
                      child: Text(
                        'Activa solo las apps que quieras escuchar.',
                      ),
                    ),
                    if (enabled.isEmpty)
                      const Padding(
                        padding: EdgeInsets.all(12),
                        child: Text('No hay aplicaciones activadas.'),
                      )
                    else
                      ...enabled.map(
                        (app) => _AppTile(
                          app: app,
                          onChanged: widget.onChanged,
                        ),
                      ),
                    const SizedBox(height: 4),
                    _SubsectionToggle(
                      title: 'Aplicaciones detectadas',
                      count: detected.length,
                      expanded: _detectedExpanded,
                      onTap: () => setState(
                        () => _detectedExpanded = !_detectedExpanded,
                      ),
                    ),
                    if (_detectedExpanded)
                      if (detected.isEmpty)
                        const Padding(
                          padding: EdgeInsets.all(12),
                          child: Text(
                            'Aún no se detectaron otras aplicaciones.',
                          ),
                        )
                      else
                        ...detected.map(
                          (app) => _AppTile(
                            app: app,
                            onChanged: widget.onChanged,
                          ),
                        ),
                    _SubsectionToggle(
                      title: 'Otras aplicaciones instaladas',
                      count: visible.length,
                      expanded: _visibleExpanded,
                      onTap: () => setState(
                        () => _visibleExpanded = !_visibleExpanded,
                      ),
                    ),
                    if (_visibleExpanded)
                      ...visible.map(
                        (app) => _AppTile(
                          app: app,
                          onChanged: widget.onChanged,
                        ),
                      ),
                  ],
                ),
              ),
          ],
        ],
      ),
    );
  }
}

class _SubsectionToggle extends StatelessWidget {
  const _SubsectionToggle({
    required this.title,
    required this.count,
    required this.expanded,
    required this.onTap,
  });

  final String title;
  final int count;
  final bool expanded;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      borderRadius: BorderRadius.circular(12),
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 10),
        child: Row(
          children: [
            Expanded(
              child: Text(
                '$title ($count)',
                style: Theme.of(context).textTheme.labelLarge,
              ),
            ),
            AnimatedRotation(
              turns: expanded ? 0.5 : 0,
              duration: const Duration(milliseconds: 180),
              child: const Icon(Icons.expand_more, size: 20),
            ),
          ],
        ),
      ),
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
  }) onChanged;

  static const _modeLabels = <String, String>{
    'SMART_YAPE': 'Yape inteligente',
    'TITLE_AND_CONTENT': 'Título y mensaje',
    'TITLE_ONLY': 'Solo título',
    'SENDER_ONLY': 'Solo remitente',
  };

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 4),
      child: Material(
        color: Theme.of(context).colorScheme.surfaceContainerLowest,
        borderRadius: BorderRadius.circular(12),
        clipBehavior: Clip.antiAlias,
        child: Column(
        children: [
          SwitchListTile(
            value: app.enabled,
            onChanged: app.installed || app.detected
                ? (value) => onChanged(app, enabled: value)
                : null,
            title: Text(app.label),
            subtitle: Text(
              app.installed
                  ? app.packageName
                  : '${app.packageName} · No instalada en este perfil',
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
            ),
            secondary: Icon(
              app.readMode == 'SMART_YAPE'
                  ? Icons.auto_awesome
                  : Icons.notifications,
            ),
          ),
          if (app.enabled)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 14),
              child: DropdownButtonFormField<String>(
                initialValue: _modeLabels.containsKey(app.readMode)
                    ? app.readMode
                    : 'TITLE_AND_CONTENT',
                isExpanded: true,
                decoration: const InputDecoration(
                  labelText: 'Qué debe leer',
                  border: OutlineInputBorder(),
                  isDense: true,
                ),
                items: _modeLabels.entries
                    .map(
                      (entry) => DropdownMenuItem<String>(
                        value: entry.key,
                        child: Text(
                          entry.value,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                        ),
                      ),
                    )
                    .toList(growable: false),
                onChanged: (value) {
                  if (value != null && value != app.readMode) {
                    onChanged(app, readMode: value);
                  }
                },
              ),
            ),
        ],
      ),
      ),
    );
  }
}

class _HistoryPanel extends StatefulWidget {
  const _HistoryPanel({required this.records, required this.onClear});

  final List<PaymentRecord> records;
  final VoidCallback onClear;

  @override
  State<_HistoryPanel> createState() => _HistoryPanelState();
}

class _HistoryPanelState extends State<_HistoryPanel> {
  bool _expanded = false;
  int _visibleCount = 20;

  @override
  Widget build(BuildContext context) {
    final records = widget.records.take(_visibleCount).toList(growable: false);
    return Card(
      clipBehavior: Clip.antiAlias,
      child: Column(
        children: [
          InkWell(
            onTap: () => setState(() => _expanded = !_expanded),
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Row(
                children: [
                  const Icon(Icons.history),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Historial',
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                        const SizedBox(height: 2),
                        Text(
                          widget.records.isEmpty
                              ? 'Aún no hay pagos detectados'
                              : '${widget.records.length} registro${widget.records.length == 1 ? '' : 's'}',
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ],
                    ),
                  ),
                  AnimatedRotation(
                    turns: _expanded ? 0.5 : 0,
                    duration: const Duration(milliseconds: 180),
                    child: const Icon(Icons.expand_more),
                  ),
                ],
              ),
            ),
          ),
          if (_expanded) ...[
            const Divider(height: 1),
            if (widget.records.isEmpty)
              const Padding(
                padding: EdgeInsets.all(20),
                child: Text('Aún no hay pagos detectados.'),
              )
            else ...[
              Padding(
                padding: const EdgeInsets.fromLTRB(8, 4, 8, 0),
                child: Align(
                  alignment: Alignment.centerRight,
                  child: TextButton.icon(
                    onPressed: widget.onClear,
                    icon: const Icon(Icons.delete_outline),
                    label: const Text('Borrar historial'),
                  ),
                ),
              ),
              ...records.map((record) => _HistoryTile(record: record)),
              if (_visibleCount < widget.records.length)
                Padding(
                  padding: const EdgeInsets.all(12),
                  child: OutlinedButton(
                    onPressed: () => setState(() => _visibleCount += 20),
                    child: const Text('Mostrar 20 más'),
                  ),
                ),
            ],
          ],
        ],
      ),
    );
  }
}

class _HistoryTile extends StatelessWidget {
  const _HistoryTile({required this.record});

  final PaymentRecord record;

  @override
  Widget build(BuildContext context) {
    return ListTile(
      leading: Icon(
        record.amount.isEmpty ? Icons.notifications_active : Icons.payments,
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
        maxLines: 3,
        overflow: TextOverflow.ellipsis,
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
    );
  }
}

class _DebugTools extends StatefulWidget {
  const _DebugTools({
    required this.result,
    required this.diagnostics,
    required this.onRun,
    required this.onListenerProbe,
  });

  final String? result;
  final Map<String, String> diagnostics;
  final ValueChanged<String> onRun;
  final VoidCallback onListenerProbe;

  @override
  State<_DebugTools> createState() => _DebugToolsState();
}

class _DebugToolsState extends State<_DebugTools> {
  bool _expanded = false;

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
      clipBehavior: Clip.antiAlias,
      child: Column(
        children: [
          InkWell(
            onTap: () => setState(() => _expanded = !_expanded),
            child: Padding(
              padding: const EdgeInsets.all(16),
              child: Row(
                children: [
                  const Icon(Icons.developer_mode),
                  const SizedBox(width: 10),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          'Diagnóstico avanzado',
                          style: Theme.of(context).textTheme.titleMedium,
                        ),
                        Text(
                          'Herramientas y datos técnicos',
                          style: Theme.of(context).textTheme.bodySmall,
                        ),
                      ],
                    ),
                  ),
                  AnimatedRotation(
                    turns: _expanded ? 0.5 : 0,
                    duration: const Duration(milliseconds: 180),
                    child: const Icon(Icons.expand_more),
                  ),
                ],
              ),
            ),
          ),
          if (_expanded)
            Padding(
              padding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Text(
                    'Estas pruebas son para diagnóstico. La pantalla principal ya muestra solo los problemas importantes.',
                  ),
                  const SizedBox(height: 12),
                  OutlinedButton.icon(
                    onPressed: widget.onListenerProbe,
                    icon: const Icon(Icons.sensors),
                    label: const Text('Probar listener real'),
                  ),
                  const SizedBox(height: 8),
                  PopupMenuButton<String>(
                    onSelected: widget.onRun,
                    itemBuilder: (context) => samples
                        .map(
                          (sample) => PopupMenuItem<String>(
                            value: sample,
                            child: Text(sample),
                          ),
                        )
                        .toList(growable: false),
                    child: const ListTile(
                      contentPadding: EdgeInsets.zero,
                      leading: Icon(Icons.science_outlined),
                      title: Text('Probar mensaje de ejemplo'),
                      trailing: Icon(Icons.arrow_drop_down),
                    ),
                  ),
                  if (widget.result != null) ...[
                    const SizedBox(height: 8),
                    SelectableText(widget.result!),
                  ],
                  if (widget.diagnostics.isNotEmpty) ...[
                    const SizedBox(height: 12),
                    const Divider(),
                    const SizedBox(height: 8),
                    SelectableText(
                      widget.diagnostics.entries
                          .where((entry) => entry.value.isNotEmpty)
                          .map((entry) => '${entry.key}: ${entry.value}')
                          .join('\n'),
                    ),
                  ],
                ],
              ),
            ),
        ],
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
    required this.installed,
    required this.userProfile,
  });

  factory ReadableApp.fromMap(Map<String, Object?> map) => ReadableApp(
    packageName: map['packageName'] as String? ?? '',
    label: map['label'] as String? ?? '',
    enabled: map['enabled'] as bool? ?? false,
    readMode: map['readMode'] as String? ?? 'TITLE_AND_CONTENT',
    detected: map['detected'] as bool? ?? false,
    installed: map['installed'] as bool? ?? false,
    userProfile: map['userProfile'] as String? ?? '',
  );

  final String packageName;
  final String label;
  final bool enabled;
  final String readMode;
  final bool detected;
  final bool installed;
  final String userProfile;
}
