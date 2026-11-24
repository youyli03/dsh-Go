import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../l10n/l10n.dart';
import '../../state/remote_control_channel.dart';
import '../settings/settings_widgets.dart';
import '../shell/page_frame.dart';

class RemoteControlPage extends StatefulWidget {
  const RemoteControlPage({super.key, this.actions = const []});

  final List<Widget> actions;

  @override
  State<RemoteControlPage> createState() => _RemoteControlPageState();
}

class _RemoteControlPageState extends State<RemoteControlPage> with WidgetsBindingObserver {
  final _host = TextEditingController();
  final _sshPort = TextEditingController(text: '22');
  final _username = TextEditingController();
  final _remotePort = TextEditingController(text: '18765');
  final _privateKey = TextEditingController();
  final _passphrase = TextEditingController();
  final _fingerprint = TextEditingController();
  Map<String, Object?>? _status;
  bool _autoStart = false;
  bool _autoReconnect = true;
  bool _allowExec = false;
  bool _execSession = false;
  bool _visible = false;
  bool _foreground = true;
  bool _reading = false;
  bool _working = false;
  bool _unavailable = false;
  String? _error;
  Timer? _poll;
  DialogRoute<bool>? _roundDialog;
  String? _roundDialogId;

  bool get _canPoll => _foreground && (_visible || _roundDialog?.isCurrent == true);

  @override
  void initState() {
    super.initState();
    final binding = WidgetsBinding.instance;
    _foreground = binding.lifecycleState == null || binding.lifecycleState == AppLifecycleState.resumed;
    binding.addObserver(this);
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    _visible = ModalRoute.isCurrentOf(context) ?? true;
    _syncPoll();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    _foreground = state == AppLifecycleState.resumed;
    _syncPoll();
  }

  void _syncPoll() {
    if (!_canPoll) {
      _poll?.cancel();
      _poll = null;
    } else if (_poll == null) {
      _poll = Timer.periodic(const Duration(seconds: 1), (_) => unawaited(_refresh()));
      unawaited(_refresh());
    }
  }

  Future<void> _refresh() async {
    if (_reading || _working || !_canPoll) return;
    _reading = true;
    try {
      final result = await RemoteControlChannel.status();
      if (!mounted || _working || !_canPoll) return;
      setState(() {
        if (_status == null) {
          _host.text = result['host']?.toString() ?? '';
          _sshPort.text = result['sshPort']?.toString() ?? '22';
          _username.text = result['username']?.toString() ?? '';
          _remotePort.text = result['remotePort']?.toString() ?? '18765';
          _autoStart = result['autoStart'] == true;
          _autoReconnect = result['autoReconnect'] != false;
          _allowExec = result['allowExec'] == true;
          _execSession = result['execSession'] == true;
          _fingerprint.text = result['fingerprint']?.toString() ?? '';
        }
        _status = result;
        _unavailable = false;
        if (_error == 'status') _error = null;
      });
      _showRoundRequest(result['state'] == 'connected' ? result['roundRequestId'] as String? : null);
    } on MissingPluginException {
      _poll?.cancel();
      _poll = null;
      if (mounted && _visible && _foreground) setState(() => _unavailable = true);
    } on PlatformException {
      if (mounted && _visible && _foreground) setState(() => _error = 'status');
    } finally {
      _reading = false;
    }
  }

  void _showRoundRequest(String? id) {
    if (_roundDialogId == id) return;
    final previous = _roundDialog;
    _roundDialog = null;
    _roundDialogId = null;
    if (previous != null && previous.isActive) previous.navigator?.removeRoute(previous);
    if (id == null) return;
    final navigator = Navigator.of(context);
    final l10n = context.l10n;
    final route = DialogRoute<bool>(
      context: context,
      barrierDismissible: false,
      builder: (dialogContext) => AlertDialog(
        content: Text(l10n.rcNextRoundRequest),
        actions: [
          TextButton(
            onPressed: () => Navigator.of(dialogContext).pop(false),
            child: Text(l10n.rcDenyNextRound),
          ),
          FilledButton(
            onPressed: () => Navigator.of(dialogContext).pop(true),
            child: Text(l10n.rcApproveNextRound),
          ),
        ],
      ),
    );
    _roundDialog = route;
    _roundDialogId = id;
    unawaited(navigator.push(route).then((approved) async {
      if (!mounted || _roundDialog != route) return;
      _roundDialog = null;
      _roundDialogId = null;
      await _act('decideRound', {'id': id, 'approved': approved == true});
    }));
  }

  Future<void> _act(String name, [Map<String, Object?>? args]) async {
    if (_working) return;
    setState(() {
      _working = true;
      _error = null;
    });
    try {
      final result = await RemoteControlChannel.action(name, args);
      if (!mounted) return;
      if (name == 'configure') {
        _privateKey.clear();
        _passphrase.clear();
      }
      if (name == 'rotateToken' && result is String && result.isNotEmpty) await _copy(result);
    } on PlatformException catch (error) {
      if (mounted) setState(() => _error = error.code);
    } on MissingPluginException {
      if (mounted) setState(() => _unavailable = true);
    } finally {
      if (mounted) {
        setState(() => _working = false);
        await _refresh();
      }
    }
  }

  bool _validPort(int? port) => port != null && port > 0 && port <= 65535;

  Future<void> _save() async {
    final sshPort = int.tryParse(_sshPort.text.trim());
    final remotePort = int.tryParse(_remotePort.text.trim());
    if (!_validPort(sshPort) ||
        !_validPort(remotePort) ||
        remotePort! < 1024 ||
        _host.text.trim().isEmpty ||
        _username.text.trim().isEmpty ||
        !RegExp(r'^SHA256:[A-Za-z0-9+/]{43}$').hasMatch(_fingerprint.text.trim()) ||
        (_privateKey.text.trim().isEmpty && _status?['hasPrivateKey'] != true)) {
      setState(() => _error = 'input');
      return;
    }
    await _act('configure', {
      'host': _host.text.trim(),
      'sshPort': sshPort,
      'username': _username.text.trim(),
      'remotePort': remotePort,
      if (_privateKey.text.trim().isNotEmpty) 'privateKey': _privateKey.text.trim(),
      'autoStart': _autoStart,
      'autoReconnect': _autoReconnect,
      'allowExec': _allowExec,
      'execSession': _execSession,
      'fingerprint': _fingerprint.text.trim(),
      if (_privateKey.text.trim().isNotEmpty) 'passphrase': _passphrase.text,
    });
  }

  Future<void> _pasteKey() async {
    final data = await Clipboard.getData(Clipboard.kTextPlain);
    if (!mounted || _working) return;
    final text = data?.text?.trim();
    if (text == null || text.isEmpty || text.length > 32768) {
      setState(() => _error = 'input');
      return;
    }
    setState(() => _privateKey.text = text);
  }

  Future<void> _copy(String value) async {
    await Clipboard.setData(ClipboardData(text: value));
    if (mounted) ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(context.l10n.rcCopied)));
  }

  @override
  void dispose() {
    _poll?.cancel();
    WidgetsBinding.instance.removeObserver(this);
    for (final field in [_host, _sshPort, _username, _remotePort, _privateKey, _passphrase, _fingerprint]) {
      field.dispose();
    }
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final status = _status ?? const <String, Object?>{};
    final connection = status['state'];
    final active = connection == 'connected' || connection == 'connecting' || connection == 'reconnecting';
    final display = switch (connection) {
      'connected' => l10n.rcConnected,
      'connecting' => l10n.rcConnecting,
      'reconnecting' => l10n.rcReconnecting,
      'error' => l10n.rcConnectionError,
      _ => _status == null && !_unavailable && _error == null ? l10n.rcLoading : l10n.rcStopped,
    };
    final activity = status['activity'] as String?;
    final permission = status['permission'] as String? ?? 'confirm';
    final hasToken = status['hasToken'] == true;
    final url = status['url'] as String?;
    final error = _error ?? status['error'] as String?;
    final disabled = _status == null || _working || _unavailable;
    return AppPage.list(
      title: l10n.remoteControlTitle,
      subtitle: Text(l10n.remoteControlSubtitle),
      actions: widget.actions,
      children: [
        SettingsSection(
          title: l10n.rcConnection,
          children: [
            SettingsTile(
              title: _unavailable ? l10n.rcUnavailable : display,
              subtitle: error == null
                  ? null
                  : error == 'input'
                  ? l10n.rcInvalidConfig
                  : l10n.rcError(error),
              trailing: active && activity != null && activity.isNotEmpty
                  ? SizedBox.square(
                      dimension: 20,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        value: MediaQuery.disableAnimationsOf(context) ? .7 : null,
                      ),
                    )
                  : Icon(active ? Icons.link : Icons.link_off),
            ),
            if (active && activity != null && activity.isNotEmpty) SettingsTile(title: l10n.rcActivity(activity)),
            if (!_unavailable)
              SettingsTile(
                title: active ? l10n.rcStop : l10n.rcStart,
                onTap: disabled || (!active && (!hasToken || status['hasPrivateKey'] != true))
                    ? null
                    : () => unawaited(_act(active ? 'stop' : 'start')),
              ),
            if (url != null) SettingsTile(title: l10n.rcCopyUrl, subtitle: url, onTap: () => unawaited(_copy(url))),
            if (!_unavailable)
              SettingsTile(
                title: hasToken ? l10n.rcRotateToken : l10n.rcCreateToken,
                onTap: disabled ? null : () => unawaited(_rotateToken(hasToken)),
              ),
          ],
        ),
        if (!_unavailable)
          SettingsSection(
            title: l10n.rcPermission,
            children: [
              SettingsSwitchTile(
                title: l10n.rcPhoneControlEnabled,
                subtitle: l10n.rcPhoneControlHint,
                value: permission == 'confirm',
                onChanged: disabled
                    ? null
                    : (enabled) => unawaited(_act('setPermission', {'permission': enabled ? 'confirm' : 'blocked'})),
              ),
            ],
          ),
        if (!_unavailable)
          SettingsSection(
            title: l10n.rcSshConfig,
            children: [
              _field(l10n.rcHost, _host, enabled: !disabled && !active),
              _field(l10n.rcSshPort, _sshPort, enabled: !disabled && !active, numeric: true),
              _field(l10n.rcUsername, _username, enabled: !disabled && !active),
              _field(l10n.rcRemotePort, _remotePort, enabled: !disabled && !active, numeric: true),
              _field(
                l10n.rcApproveFingerprint,
                _fingerprint,
                enabled: !disabled && !active,
                helper: l10n.rcVerifyFingerprint,
              ),
              SettingsTile(
                title: l10n.rcPasteKey,
                subtitle: _privateKey.text.isNotEmpty
                    ? l10n.rcKeyStaged
                    : status['hasPrivateKey'] == true
                    ? l10n.rcKeySaved
                    : l10n.rcPrivateKey,
                onTap: disabled || active ? null : () => unawaited(_pasteKey()),
              ),
              _field(l10n.rcPassphrase, _passphrase, enabled: !disabled && !active, secret: true),
              SettingsSwitchTile(
                title: l10n.rcAllowExec,
                value: _allowExec,
                onChanged: disabled || active ? null : (value) => setState(() => _allowExec = value),
              ),
              if (_allowExec)
                SettingsSwitchTile(
                  title: l10n.rcExecSession,
                  subtitle: l10n.rcExecSessionHint,
                  value: _execSession,
                  onChanged: disabled || active ? null : (value) => setState(() => _execSession = value),
                ),
              SettingsSwitchTile(
                title: l10n.rcAutoStart,
                value: _autoStart,
                onChanged: disabled || active ? null : (value) => setState(() => _autoStart = value),
              ),
              SettingsSwitchTile(
                title: l10n.rcAutoReconnect,
                subtitle: l10n.rcAutoReconnectHint,
                value: _autoReconnect,
                onChanged: disabled || active ? null : (value) => setState(() => _autoReconnect = value),
              ),
              SettingsTile(title: l10n.rcSave, onTap: disabled || active ? null : () => unawaited(_save())),
            ],
          ),
      ],
    );
  }

  Future<void> _rotateToken(bool hasToken) async {
    final l10n = context.l10n;
    if (hasToken) {
      final confirmed = await showDialog<bool>(
        context: context,
        builder: (context) => AlertDialog(
          title: Text(l10n.rcRotateToken),
          content: Text(l10n.rcRotateTokenHint),
          actions: [
            TextButton(onPressed: () => Navigator.pop(context, false), child: Text(l10n.rcCancel)),
            FilledButton(onPressed: () => Navigator.pop(context, true), child: Text(l10n.rcRotateToken)),
          ],
        ),
      );
      if (!mounted || confirmed != true) return;
    }
    await _act('rotateToken');
  }

  Widget _field(
    String label,
    TextEditingController controller, {
    required bool enabled,
    bool numeric = false,
    bool secret = false,
    String? helper,
  }) => Padding(
    padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
    child: TextField(
      controller: controller,
      enabled: enabled,
      obscureText: secret,
      autocorrect: false,
      enableSuggestions: false,
      keyboardType: numeric ? TextInputType.number : TextInputType.text,
      decoration: InputDecoration(labelText: label, helperText: helper, helperMaxLines: 3),
    ),
  );
}
