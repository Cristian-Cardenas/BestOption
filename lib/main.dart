import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const OverlayApp());

class OverlayApp extends StatelessWidget {
  const OverlayApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'BestOption',
        debugShowCheckedModeBanner: false,
        theme: _darkTheme(),
        home: const OverlayHomePage(),
      );

  // -------- Tema oscuro minimalista --------
  static const Color _bg = Color(0xFF0C0F14);
  static const Color _surface = Color(0xFF151A22);
  static const Color _border = Color(0xFF252C38);
  static const Color _text = Color(0xFFE6EBF3);
  static const Color _muted = Color(0xFF8B95A8);
  static const Color _accent = Color(0xFF5BE0B7);

  static ThemeData _darkTheme() {
    final base = ThemeData(brightness: Brightness.dark, useMaterial3: true);
    return base.copyWith(
      scaffoldBackgroundColor: _bg,
      colorScheme: const ColorScheme.dark(
        primary: _accent,
        onPrimary: Color(0xFF0A0E12),
        surface: _surface,
        onSurface: _text,
        onSurfaceVariant: _muted,
        error: Color(0xFFFF6B5E),
        onError: Color(0xFF1A0D0B),
      ),
      appBarTheme: const AppBarTheme(
        backgroundColor: _bg,
        elevation: 0,
        foregroundColor: _text,
        centerTitle: true,
        titleTextStyle: TextStyle(
          color: _text,
          fontSize: 19,
          fontWeight: FontWeight.w600,
          letterSpacing: 0.4,
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: _surface,
        labelStyle: const TextStyle(color: _muted, fontSize: 14),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: _border),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(12),
          borderSide: const BorderSide(color: _accent, width: 1.4),
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          backgroundColor: _accent,
          foregroundColor: const Color(0xFF0A0E12),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
          padding: const EdgeInsets.symmetric(vertical: 14),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          foregroundColor: _text,
          side: const BorderSide(color: _border),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(12)),
          padding: const EdgeInsets.symmetric(vertical: 14),
        ),
      ),
      snackBarTheme: const SnackBarThemeData(
        backgroundColor: _surface,
        contentTextStyle: TextStyle(color: _text),
        behavior: SnackBarBehavior.floating,
      ),
      dividerTheme: const DividerThemeData(color: _border, thickness: 1),
    );
  }
}

class OverlayHomePage extends StatefulWidget {
  const OverlayHomePage({super.key});
  @override
  State<OverlayHomePage> createState() => _OverlayHomePageState();
}

class _OverlayHomePageState extends State<OverlayHomePage> with WidgetsBindingObserver {
  static const _channel = MethodChannel('com.example.bo2/overlay');
  bool _permissionGranted = false;
  bool _running = false;

  final _minCtrl = TextEditingController(text: '1100');
  final _maxCtrl = TextEditingController(text: '1300');
  final _timeMinCtrl = TextEditingController(text: '450');
  final _timeMaxCtrl = TextEditingController(text: '650');
  bool _saved = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refreshState();
    _loadConfig();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _minCtrl.dispose();
    _maxCtrl.dispose();
    _timeMinCtrl.dispose();
    _timeMaxCtrl.dispose();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refreshState();
  }

  Future<void> _refreshState() async {
    try {
      final granted = await _channel.invokeMethod<bool>('isOverlayPermissionGranted') ?? false;
      if (mounted) setState(() => _permissionGranted = granted);
    } on MissingPluginException {
      // Running on non-Android platforms (for example, widget tests).
    }
  }

  // Interpreta el texto como número, ignorando separadores de miles (1.100 | 1100).
  double _parseRate(String s) {
    final t = s.trim().replaceAll('.', '').replaceAll(',', '');
    return double.tryParse(t) ?? 0;
  }

  String _formatRate(double v) => v.toStringAsFixed(0);

  Future<void> _loadConfig() async {
    try {
      final cfg = await _channel.invokeMethod<Map>('getConfig');
      if (cfg != null) {
        _minCtrl.text = _formatRate((cfg['rateMin'] as num).toDouble());
        _maxCtrl.text = _formatRate((cfg['rateMax'] as num).toDouble());
        _timeMinCtrl.text = _formatRate((cfg['timeMin'] as num).toDouble());
        _timeMaxCtrl.text = _formatRate((cfg['timeMax'] as num).toDouble());
        if (mounted) setState(() => _saved = true);
      }
    } on MissingPluginException {
      // widget test
    }
  }

  Future<void> _saveConfig() async {
    final min = _parseRate(_minCtrl.text);
    final max = _parseRate(_maxCtrl.text);
    final tmin = _parseRate(_timeMinCtrl.text);
    final tmax = _parseRate(_timeMaxCtrl.text);
    await _channel.invokeMethod('saveConfig', {
      'rateMin': min,
      'rateMax': max,
      'timeMin': tmin,
      'timeMax': tmax,
    });
    if (mounted) {
      setState(() {
        _minCtrl.text = _formatRate(min);
        _maxCtrl.text = _formatRate(max);
        _timeMinCtrl.text = _formatRate(tmin);
        _timeMaxCtrl.text = _formatRate(tmax);
        _saved = true;
      });
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Configuración guardada')));
    }
  }

  Future<void> _resetConfig() async {
    await _channel.invokeMethod('resetConfig');
    await _loadConfig();
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Valores restaurados')));
    }
  }

  Future<void> _start() async {
    if (!_permissionGranted) {
      await _channel.invokeMethod('requestOverlayPermission');
      return;
    }
    await _channel.invokeMethod('startOverlay');
    if (mounted) setState(() => _running = true);
  }

  Future<void> _stop() async {
    await _channel.invokeMethod('stopOverlay');
    if (mounted) setState(() => _running = false);
  }

  @override
  Widget build(BuildContext context) => Scaffold(
        appBar: AppBar(title: const Text('Ajustes')),
        body: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: ConstrainedBox(
              constraints: const BoxConstraints(maxWidth: 420),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  // ---- Estado / control del servicio ----
                  Container(
                    padding: const EdgeInsets.all(16),
                    decoration: BoxDecoration(
                      color: OverlayApp._surface,
                      borderRadius: BorderRadius.circular(16),
                      border: Border.all(color: OverlayApp._border),
                    ),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.stretch,
                      children: [
                        Text(
                          _permissionGranted
                              ? 'Permiso de superposición concedido'
                              : 'Falta permiso de superposición',
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            fontSize: 14,
                            fontWeight: FontWeight.w500,
                            color: _permissionGranted ? OverlayApp._accent : Theme.of(context).colorScheme.error,
                          ),
                        ),
                        const SizedBox(height: 14),
                        FilledButton.icon(
                          onPressed: _start,
                          icon: const Icon(Icons.play_arrow),
                          label: Text(_permissionGranted ? 'Iniciar' : 'Conceder permiso'),
                        ),
                        const SizedBox(height: 10),
                        OutlinedButton.icon(
                          onPressed: _running ? _stop : null,
                          icon: const Icon(Icons.stop),
                          label: const Text('Detener'),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 20),
                  const Divider(),
                  const SizedBox(height: 16),

                  // ---- Umbrales ----
                  Text('Umbrales', style: Theme.of(context).textTheme.titleMedium?.copyWith(fontWeight: FontWeight.w600)),
                  const SizedBox(height: 4),
                  Text(
                    'COP/km: menor que el mínimo = MALO · entre = REGULAR · mayor que el máximo = BUENO\nCOP/min: el mismo criterio aplicado a sus dos umbrales.',
                    style: Theme.of(context).textTheme.bodySmall?.copyWith(color: OverlayApp._muted, height: 1.4),
                  ),
                  const SizedBox(height: 16),
                  _field(_minCtrl, 'Mínimo normal (COP/km)'),
                  const SizedBox(height: 12),
                  _field(_maxCtrl, 'Máximo normal (COP/km)'),
                  const SizedBox(height: 14),
                  _field(_timeMinCtrl, 'Mínimo normal (COP/min)'),
                  const SizedBox(height: 12),
                  _field(_timeMaxCtrl, 'Máximo normal (COP/min)'),
                  const SizedBox(height: 20),
                  Row(
                    children: [
                      Expanded(
                        child: FilledButton.tonal(onPressed: _resetConfig, child: const Text('Restaurar')),
                      ),
                      const SizedBox(width: 12),
                      Expanded(
                        child: FilledButton(onPressed: _saveConfig, child: Text(_saved ? 'Guardado ✓' : 'Guardar')),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ),
      );

  Widget _field(TextEditingController c, String label) => TextField(
        controller: c,
        keyboardType: TextInputType.number,
        decoration: InputDecoration(labelText: label),
      );
}