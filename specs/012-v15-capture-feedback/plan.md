# Implementation Plan: v15撮影画面フィードバック修正

**Branch**: `012-v15-capture-feedback` | **Date**: 2026-11-03 | **Spec**: [spec.md](spec.md)
**Input**: Issue #47の追加受入条件。specify → plan → tasks → analyzeを適用する。

## Summary

**後続設計**: 本planのEV/WB busyによる選択拒否・MF適用時のみ送信は[013 plan](../013-live-control-intent/plan.md)の単一logical intent queueとMF即時previewへ置換する。AUTO正常観測はdiagnostics throttle前の軸別集約へ補正する。本文は012の設計境界として保持し、013の採用範囲と競合する箇所は013を優先する。

正常なAUTO実測を軸別に保持し、MANUAL主値を最新撮影targetへ切り替える。シャッターの内円を58/48dpへ、EV/WB/MFの目盛りを既存ISO/SS共通選択へ統一する。Android自動回転の読み取りでUI向きだけを制御し、物理出力向きと録画開始固定を維持する。

## Technical Context

**Language/Version**: 既存Kotlin / Java 17設定を維持。
**Primary Dependencies**: Jetpack Compose、CameraX 1.6.2、Android標準Settings/ContentObserver/lifecycle。依存更新なし。
**Storage**: 既存MediaStoreとアプリ内設定を維持。新永続化なし。
**Testing**: 既存JUnitのpure policy境界、接続箇所の静的照合、通常Android CI。実機は別AC。
**Target Platform**: Android minSdk 28 / targetSdk 37。Pixel 9aでの実機結果は未確認。
**Project Type**: 単一Android app。
**Performance Goals**: 欠落観測による不要空白0件、元取得時刻から2000ms超の値採用0件。即時操作の同時要求数は1以下。
**Constraints**: camera設定/pipeline・権限・依存・署名・RAWツール変更なし。表示値の設定書戻しなし。
**Scale/Scope**: 撮影画面の6件。AUTOちらつき、MANUAL表示、シャッター、EV/WB目盛り、MF目盛り、自動回転。

## Constitution Check

設計前・設計後ともI〜VIを確認。仕様/実装/実機を分離し、標準APIを使い、原本・MediaStore・ライブ品質を維持する。新しいカメラ処理、権限、依存、個人情報公開はない。例外・違反なし。Draft/review/CIまでとし、merge/Releaseは未承認。

## Project Structure

### Documentation (this feature)

`specs/012-v15-capture-feedback/{spec.md,plan.md,tasks.md}`。標準resolver/setup-plan/setup-tasksを使用。`docs/SPEC_KIT.md`に従いresearch/データ構造は本planへ統合し、quickstartはREADME/P0_REPORTへ参照、外部API contractsと別checklistは増やさない。品質checkはspecの全必須節・テスト可能性・スコープ・曖昧語・技術非依存SCを会話内で照合する。

### Source Code (repository root)

- `app/src/main/java/dev/cinematic/p0/ExposureValuePresentation.kt`、`CaptureState.kt`、`CameraProbe.kt`、`CaptureScreen.kt`: 正常観測slot、世代/期限、主値sourceの投影。
- `app/src/main/java/dev/cinematic/p0/CaptureChromeModel.kt`、`CaptureChrome.kt`: 内円寸法と既存操作領域。
- `app/src/main/java/dev/cinematic/p0/ControlScale.kt`、`ExposureControls.kt`、`SensorControlEditors.kt`、`CapturePanels.kt`: 共通detent/actionとEV/WB/MF wrapper。
- `app/src/main/java/dev/cinematic/p0/MainActivity.kt`、`CaptureOrientation.kt`: 標準設定読取とUI/物理向き分離。
- `app/src/test/java/dev/cinematic/p0/`: 対応する既存policyテストを拡張。

## Phase 0: Research / Decisions

1. **Decision**: AUTOにはISO/SSそれぞれの最後の正常なPREVIEW/VIDEO_RECORD sampleを保持する。**Rationale**: frame全体の一時欠落で両軸を消さず、各元timestampを検証できる。**Alternative**: 最新frameだけを使う案は欠落でちらつく。古い値の無期限保持も不採用。
2. **Decision**: MANUALはlatest live target、現世代の固定STILL target、要求/適用targetの既存優先順位で投影する。受付より新しい意図を優先し、内部補償preview requestを主値にしない。AUTO移行と旧世代は別途検査する。**Alternative**: 常時preview主値は今回のPO要件と矛盾する。
3. **Decision**: 既存`ExposureSelectionScale`と距離蓄積policyを再利用し、同期`canSelect`でEV/WB busyを選択/触覚より前に拒否する。EVは絶対indexを既存applyEvへ渡し、現在CameraX indexとの差分による誤選択を避ける。MFは同じUIをdraft更新へ接続し、送信は適用時だけ。reported maxの100等分候補へ現exact値をanchorとして保持する。**Alternative**: 新gesture/haptic基盤やMFの即時送信は不要。
4. **Decision**: 標準`Settings.System.ACCELEROMETER_ROTATION`を読み取り、前面中は`ContentObserver`、resume時は再読取する。物理orientationを更新し続け、`forUi`のみに設定を適用する。**Alternative**: Activity全体回転・設定書換え・権限追加は固定配置と出力契約を壊す。

## Phase 1: Integrated Data Model / Contracts

- **正常観測slot**: current epoch、ISO用sample/SS用sample。正常な現世代preview intentの正値だけ更新し、欠落/不正/別用途/旧観測は置換しない。取得時刻はcallback内の単調時計で採番した観測元を保持する。正常sourceの時刻を保持し、表示境界へ負/未来時刻を渡した場合は「—」とする。epoch変更とunbindで消去。投影時に現在時刻との差が0〜2000msかを各軸検査する。
- **主値source**: AUTO=`PREVIEW_ACTUAL`（近似ラベル）、MANUAL=`CAPTURE_TARGET`（設定値ラベル）。どちらも表示専用でcamera設定を作らない。RAW固定targetは現epochのみ。補償の要求/受付/実測は診断に残す。
- **共通選択状態**: count/current/target/enabledと距離残量。実選択変更かつ同期guard通過後のみ選択callbackと標準haptic(flags 0)。無効/端点/同値/拒否は両方なし。EV/WBは既存single outstanding、MFはdraft/apply/cancel。
- **向き状態**: physical sensorQuarter、uiAngle、autoRotateEnabled、output latest/applied、録画開始向き。OFFではuiAngleの投影先を0にし、physicalを維持。ON/toggle/resumeは最新physicalで再投影。出力はphysicalから既存public setterへ渡し、撮影/録画active中のfreezeを維持する。
- **互換性**: Spec009 FR-010/US2の主値とSpec003 US3/FR-007のUI回転だけをSpec012で明示置換。58/48dpは内円だけでouter tap/a11yは維持。

## Validation / Quickstart References

既存[README](../../README.md)、[P0_REPORT](../../docs/P0_REPORT.md)、[BUILD_VALIDATION](../../docs/BUILD_VALIDATION.md)を参照する。機械確認は通常の `./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug --no-daemon`（Windowsは`gradlew.bat`）、`git diff --check`と公開情報検査。CI結果は実際のAndroid workflowを根拠とし、実行していない検証を合格にしない。

実機はspecのUS1〜4とSC1〜4を独立に照合する。2秒期限/旧epochと最新target、58/48内円、EV/WB即時busy、MF適用/取消、標準触覚と端点、OFF/ON×4方向・設定途中切替・復帰・録画1本/出力開始方向を確認する。6件の結果をIssue/PRコメントへ記録し、既存v15案内4点の確認で代替しない。最終結果だけ必要時P0_REPORTへ反映する。

## Complexity Tracking

憲法違反・新基盤・外部API契約なし。未解決の仕様判断なし。実機ACと最終headレビュー/CIはtasksで未完了として保持する。
