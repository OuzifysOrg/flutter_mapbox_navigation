import 'package:flutter/services.dart';
import 'package:flutter_mapbox_navigation/flutter_mapbox_navigation.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('flutter_mapbox_navigation/17');
  late MapBoxNavigationViewController controller;
  final calls = <MethodCall>[];

  setUp(() {
    calls.clear();
    controller = MapBoxNavigationViewController(17, null);
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      switch (call.method) {
        case 'getVoiceMuted':
          return true;
        case 'setVoiceMuted':
          return call.arguments['muted'] as bool;
      }
      return null;
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  test('reads the native embedded voice state', () async {
    expect(await controller.getVoiceMuted(), isTrue);
    expect(calls.single.method, 'getVoiceMuted');
  });

  test('sends the requested embedded voice state', () async {
    expect(await controller.setVoiceMuted(false), isFalse);
    expect(calls.single.method, 'setVoiceMuted');
    expect(calls.single.arguments, {'muted': false});
  });
}
