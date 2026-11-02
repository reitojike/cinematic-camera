# Validation
`./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug --no-daemon`、`git diff --check`。Windowsはgradlew.bat。
自動: 旧epoch/frame破棄、実測と受付区別、AUTO保持/全reset、draft全経路、向き境界/最短経路/出力固定、FIT/insets。
実機（自動合格にしない）:
1. ISO/SS編集中に取消/閉じる/STILL撮影、受付/実測と見た目を照合。
2. WB/MF設定後に露出AUTO、その後二段階reset。各設定の保持/解除を確認。
3. STILL/CINEと設定/診断、Back、非対応/保存失敗、マイク拒否を確認。
4. 4方向へ持ち替えながら録画し、1件MP4/音声/向き、静止JPEG/DNG向きを確認。
5. 背景化停止と復帰、熱はOS実状態のみ記録。
