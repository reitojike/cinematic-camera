# Implementation Plan: 固定pair露出simulation
**Branch**: feat/009-exposure-simulation | **Spec**: [spec.md](spec.md) | **Issue**: #42

## Summary
PR41の上に明示opt-inのSTILL限定検証を追加。固定capture pairはImageCapture.Builder、計算preview pairはbind後Camera2CameraControlで指定する。通常受付履歴とsimulation用preview receiptを別管理する。

## Technical Context
Kotlin/Compose/CameraX1.6.2/Android28+既存版。依存追加なし。MediaStore/RAW処理は既存。pure unit・build/lintと静的/API確認、実機は別途ユーザー操作。

## Constitution Check
標準公開APIのみ、CameraDevice/Session直接操作なし。第三描画mode/露出計/GPU/NDKなし。実機結果と機械検証を分離。snapshot/ログに画像/位置情報なし。Draft→独立/canonical/CIまで、merge許可は別。

## Design
- ExposureSimulationPolicy.kt: exact整数積/端末range/二段階。ISO補償内は短露光、上限時は時間延長。boost共通指定では積から相殺、未制御は明示。
- ExposureSimulationRun.kt: fixed normal snapshot、専用preview receipt、intent別frame、ENTERING/READY/CAPTURING/RESTORING/UNKNOWN/SUSPENDEDとexitPending。これらは制御phaseで描画Stageは2種類のみ。
- CameraProbe.kt: 固定Builder/runtime options、初回bind混入記録、watchdog、backend変更guard、photo保存とpreview復帰を分離。背景化は先に無効化、normal rebindはforegroundへ延期。closeは再bindしない。
- CaptureScreen.kt: settingsのopt-in/exit、capture/preview target/実測とgain/周期の明示。検証中通常操作を禁止、capture pairを補償値で保存しない。

## Scope / Complexity
撮影pairを検証開始時に固定。変更は終了後。CINEへ移る前に検証設定を破棄して通常bind。Frame intentはファイルとの厳密な一対一証明ではない。実機で不成立なら独自回避へ広げない。
