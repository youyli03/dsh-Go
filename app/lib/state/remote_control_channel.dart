import 'package:flutter/services.dart';

abstract final class RemoteControlChannel {
  static const _channel = MethodChannel('dsh_go/remote_control');

  static Future<Map<String, Object?>> status() async =>
      await _channel.invokeMapMethod<String, Object?>('status') ?? const {};

  static Future<Object?> action(String name, [Map<String, Object?>? arguments]) =>
      _channel.invokeMethod<Object?>(name, arguments);

  static Future<void> autoStart() async {
    try {
      await action('autoStart');
    } on MissingPluginException {
      // Older Android hosts do not have this channel.
    } on PlatformException {
      // The remote-control page reads startup failures from native status.
    }
  }
}
