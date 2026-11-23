import 'package:dsh_app/l10n/l10n.dart';
import 'package:dsh_app/ui/remote_control/remote_control_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import '../shell/golden_harness.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('dsh_go/remote_control');
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  final l10n = lookupL10n(AppLocale.zhCN);
  late List<MethodCall> calls;
  late Map<String, Object?> status;

  setUp(() {
    calls = [];
    status = {
      'host': 'server.example',
      'sshPort': 22,
      'username': 'phone',
      'remotePort': 18765,
      'fingerprint': 'SHA256:${'a' * 43}',
      'hasPrivateKey': true,
      'hasToken': true,
      'autoStart': false,
      'allowExec': false,
      'state': 'stopped',
      'error': null,
      'permission': 'confirm',
      'locked': false,
      'activity': null,
      'url': null,
    };
    messenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (call.method == 'status') return status;
      if (call.method == 'rotateToken') return 'token-value';
      return status;
    });
  });

  tearDown(() => messenger.setMockMethodCallHandler(channel, null));

  Future<void> pump(WidgetTester tester) async {
    usePhoneSurface(tester);
    await tester.pumpWidget(
      goldenApp(home: const RemoteControlPage(), brightness: Brightness.light),
    );
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 50));
  }

  Future<void> dispose(WidgetTester tester) async {
    await tester.pumpWidget(const SizedBox());
  }

  testWidgets('start sends start and the connection is shown as stopped', (tester) async {
    await pump(tester);
    expect(find.text(l10n.rcStopped), findsOneWidget);
    await tester.tap(find.text(l10n.rcStart));
    await tester.pump();
    expect(calls.map((call) => call.method), contains('start'));
    await dispose(tester);
  });

  testWidgets('a permission choice is applied through native state', (tester) async {
    await pump(tester);
    final control = find.text(l10n.rcPhoneControlEnabled);
    await tester.ensureVisible(control);
    await tester.tap(control);
    await tester.pump();
    final permission = calls.singleWhere((call) => call.method == 'setPermission');
    expect(permission.arguments, {'permission': 'blocked'});
    await dispose(tester);
  });

  testWidgets('an invalid fingerprint blocks configure and says why', (tester) async {
    await pump(tester);
    await tester.enterText(find.widgetWithText(TextField, l10n.rcApproveFingerprint), 'not-a-fingerprint');
    final save = find.text(l10n.rcSave);
    await tester.ensureVisible(save);
    await tester.pump();
    await tester.tap(save);
    await tester.pump();
    expect(calls.map((call) => call.method), isNot(contains('configure')));
    await tester.drag(find.byType(Scrollable).first, const Offset(0, 2000));
    await tester.pump();
    expect(find.text(l10n.rcInvalidConfig), findsOneWidget);
    await dispose(tester);
  });

  testWidgets('activity shows while a remote call runs', (tester) async {
    status = {...status, 'state': 'connected', 'url': 'http://127.0.0.1:18765/mcp', 'activity': 'screen'};
    await pump(tester);
    expect(find.text(l10n.rcActivity('screen')), findsOneWidget);
    expect(find.text('http://127.0.0.1:18765/mcp'), findsOneWidget);
    await dispose(tester);
  });

  testWidgets('session tools are a separate saved choice for prompt delegation', (tester) async {
    status = {...status, 'allowExec': true};
    await pump(tester);
    final sessionTools = find.text(l10n.rcExecSession);
    await tester.drag(find.byType(Scrollable).first, const Offset(0, -750));
    await tester.pumpAndSettle();
    await tester.ensureVisible(sessionTools);
    await tester.pump();
    await tester.tap(sessionTools);
    await tester.pump();
    final save = find.text(l10n.rcSave);
    await tester.ensureVisible(save);
    await tester.pump();
    await tester.tap(save);
    await tester.pump();
    final config = calls.singleWhere((call) => call.method == 'configure').arguments as Map;
    expect(config['allowExec'], isTrue);
    expect(config['execSession'], isTrue);
    await dispose(tester);
  });

  for (final approved in [true, false]) {
    testWidgets('next-round decision is bound to the shown request ($approved)', (tester) async {
      status = {...status, 'state': 'connected', 'roundRequestId': 'round-request'};
      await pump(tester);
      final decision = find.text(approved ? l10n.rcApproveNextRound : l10n.rcDenyNextRound);
      await tester.ensureVisible(decision);
      await tester.tap(decision);
      await tester.pump();
      expect(calls.singleWhere((call) => call.method == 'decideRound').arguments, {
        'id': 'round-request',
        'approved': approved,
      });
      await dispose(tester);
    });
  }
}
