import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() => runApp(const OverlayApp());

class OverlayApp extends StatelessWidget {
  const OverlayApp({super.key});

  @override
  Widget build(BuildContext context) => MaterialApp(
          title: 'BestOption',
          theme: ThemeData(colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo), useMaterial3: true),
          home: const OverlayHomePage(),
        );
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
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Configuración guardada')));
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
        appBar: AppBar(title: const Text('BestOption')),
        body: Center(
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: Column(mainAxisSize: MainAxisSize.min, crossAxisAlignment: CrossAxisAlignment.stretch, children: [
              const Text('Muestra la tarifa por km sobre otras aplicaciones y la clasifica.', textAlign: TextAlign.center),
              const SizedBox(height: 20),
              Text(_permissionGranted ? 'Permiso de superposición concedido' : 'Falta permiso de superposición',
                  textAlign: TextAlign.center),
              const SizedBox(height: 20),
              FilledButton.icon(
                onPressed: _start,
                icon: const Icon(Icons.play_arrow),
                label: Text(_permissionGranted ? 'Iniciar' : 'Conceder permiso'),
              ),
              const SizedBox(height: 12),
              OutlinedButton.icon(onPressed: _running ? _stop : null, icon: const Icon(Icons.stop), label: const Text('Detener')),
              const Divider(height: 40),

              // ------- Ajustes -------
              Text('Ajustes', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 8),
              Text('Tarifa por km: menor al mínimo = MALO · entre = REGULAR · mayor al máximo = BUENO\nTarifa por min: igual criterio con sus umbrales',
                                style: Theme.of(context).textTheme.bodySmall),
              const SizedBox(height: 16),
              TextField(
                controller: _minCtrl,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(labelText: 'Mínimo normal (COP/km)', border: OutlineInputBorder()),
              ),
              const SizedBox(height: 12),
              TextField(
                              controller: _maxCtrl,
                              keyboardType: TextInputType.number,
                              decoration: const InputDecoration(labelText: 'Máximo normal (COP/km)', border: OutlineInputBorder()),
                            ),
                            const SizedBox(height: 16),
                            TextField(
                              controller: _timeMinCtrl,
                              keyboardType: TextInputType.number,
                              decoration: const InputDecoration(labelText: 'Mínimo normal (COP/min)', border: OutlineInputBorder()),
                            ),
                            const SizedBox(height: 12),
                            TextField(
                              controller: _timeMaxCtrl,
                              keyboardType: TextInputType.number,
                              decoration: const InputDecoration(labelText: 'Máximo normal (COP/min)', border: OutlineInputBorder()),
                            ),
                            const SizedBox(height: 16),
              Row(children: [
                Expanded(
                  child: FilledButton.tonal(onPressed: _resetConfig, child: const Text('Restaurar')),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: FilledButton(onPressed: _saveConfig, child: Text(_saved ? 'Guardado ✓' : 'Guardar')),
                ),
              ]),
            ]),
          ),
        ),
      );
}