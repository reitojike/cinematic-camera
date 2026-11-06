# Implementation Plan: 診断画面

Branch: feat/design-diagnostics / Issue #173 / Spec: [spec.md](spec.md)

## Summary / Technical Context

既存Kotlin/Compose/Material3/CameraX1.6.2。依存追加なし、状態の所有と保存は変更しない。AのCaptureDesign後に診断用shellとtab bodyへ分離する。CaptureScreenの診断overlay全体を維持。

## Constitution Check

要求/受付/実測を分離。原本/RAW処理/公開を変更せず、実機未検証を区別。標準scrollとSelectionContainer。合格のために既存情報を削らない。

## Project Structure / 順序

app/src/main/java/dev/cinematic/p0/CaptureDiagnosticsSheet.ktを追加し、CaptureScreen.ktの診断header/bodyを置換。CapturePanels.ktの診断本文をtab単位へ分割し、CaptureExposureSummaryの復旧操作を出力タブへ維持。
A共有変更は依存として取り込み、BのPR差分は診断だけにする。

## 情報対応表

| 既存 | 移動先 |
|---|---|
| CaptureExposureSummary・保存結果・停止復旧 | 出力 |
| epoch/API phase/detail | 要約と制御 |
| controlDiagnosticTable・frame対応注記 | 制御 |
| exposureDisplayDiagnostic・2000ms説明・最新intent | 制御 |
| exposurePolicyStatus/stillResolution/videoSelection | 出力 |
| version/mode/ID/bound/controlReady/outputOrientation | 出力 |
| recordingStage/stats/audio・thermal/focus/ev/lock | 出力 |
| measured/status | 出力 |
| cameraXReports/reports.text | 能力 |
| lines | ログ |
| bind/stop/exportとprivacy注記 | 固定footer |

期限バーは既存sample取得時刻とnowMsの差を表示するだけにし、値の採用/epoch判定は既存関数を継続。取得できない時刻を0msと偽装しない。能力やログの自由文から制御の成功を判定しない。ログ原文は保持し、既存の明示的な ACCEPTED / Accepted= トークンを含む行だけ受付の緑で強調する。他の自由文は中立色とし、この表示は状態判定や実測の採用に使わない。

## 検証

既存unitとlint/assemble、diff check。表の全項目をコードで追跡し各タブを表示確認。実機の読みやすさ/操作感は未検証としてPRへ残す。merge/APPROVE/配布なし。

短辺・拡大文字では見出し/要約/タブ/footerの折返しを考慮し、高さ520dp×文字倍率未満、または幅360dp×文字倍率未満なら全体scrollへ切り替える。通常412×924dp・等倍ではfooter固定を維持する。
