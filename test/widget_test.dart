import 'package:flutter_test/flutter_test.dart';
import 'package:bo2/main.dart';

void main() {
  testWidgets('shows overlay controls', (tester) async {
    await tester.pumpWidget(const OverlayApp());
    expect(find.text('BO2 Overlay'), findsOneWidget);
    expect(find.text('Conceder permiso'), findsOneWidget);
    expect(find.text('Detener'), findsOneWidget);
  });
}
