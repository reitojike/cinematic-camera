# Implementation Plan: 撮影UIの状態・制御値・診断整理

**Branch**: `feat/012-capture-ui-polish` | **Date**: 2026-11-02 | **Spec**: [spec.md](spec.md)
**Input**: Issue #47と追加PO comments [6583372005](https://github.com/reitojike/cinematic-camera/issues/47#issuecomment-6583372005) / [6583452413](https://github.com/reitojike/cinematic-camera/issues/47#issuecomment-6583452413)。後の情報集約基準を最優先する。

## Summary

撮影画面の情報を一か所ずつに割り当てる。キーは近似preview系実測、目盛りは独立した撮影target選択、露出mode/影響項目の送信状態は共通領域、進捗は状態バー、失敗/無効理由はnotice、詳細比較は診断へ集約する。下captionは描画しない。camera pipeline/保存の意味を変えず、既存のISO/SS即時反映・MF draft・WB即時選択を維持する。

## Technical Context

既存Kotlin/Compose Material3・Foundation、Android 28+単一app、CameraX 1.6.2。依存/権限/ストレージ追加なし。標準View hapticのflags 0を使い端末/ユーザー設定を尊重する。JUnit pure policyと既存state/layout/rotation/editor回帰、lint/build、callsite静的照合を用いる。表示更新はcamera rebind/設定書戻しを起こさない。

## Constitution Check

事前/設計後とも適合。Issueと最新POをauthorityとし、構造化状態から要求/受付/実測/保存を区別する。表示近似をセンサー反映や画質の証拠にしない。標準Compose/Viewを使い独自UI/振動/Android mock基盤を増やさない。RAW/MediaStore保存、CameraX固定版を維持。公開素材/秘密/個人パスなし。最新headのDraft PRレビュー/CI/threadを確認し、merge/配布は別承認。

## Project Structure

- `app/src/main/java/dev/cinematic/p0/CaptureScreen.kt`: キー主値/共通状態/notice/目盛り/各panel接続。
- `CaptureChromeModel.kt` / `CaptureChrome.kt`: シャッターと状態バーpolicy/描画。下caption描画を除去し、accessibility状態は保持。
- `CaptureFeedback.kt` / `CapturePanels.kt`: 項目別変更判定、共通送信状態、無効/失敗notice、診断source比較とsettings選択操作。
- `SensorControlPresentation.kt` / `SensorControlEditors.kt`: MF/WB表示と既存draft/選択action。無関係項目へ全snapshot pendingを伝播しない。
- `ExposureValuePresentation.kt` / `app/src/test/java/dev/cinematic/p0/ExposureValuePresentationTest.kt`: 2000ms鮮度/intent/近似候補/論理target差の純粋policyと回帰。CaptureStateのCaptureSampleIntentとCameraProbeの観測metadataを入力にし、camera要求を書き換えない。
- `ExposureControls.kt` / `ControlScale.kt`: ISO/SS主値・mode配置と距離蓄積detent。標準候補への実測近似は表示専用。
- `app/src/test/java/dev/cinematic/p0/`の対応pure表示/scale/state/layout/rotation testsで検証。ExposureValuePresentationTestが新規pure主値責務を検証する。
- `specs/009-capture-ui-states/{spec.md,plan.md,tasks.md,checklists/requirements.md}`。

上記の短いsource名はすべて`app/src/main/java/dev/cinematic/p0/`配下。research/model/validationは本planへ統合し外部API契約は追加しない（docs/SPEC_KIT.md）。ShutterCaptionLayoutは削除し、下captionを描画しない。

## Research / 採用判断

**Decision**: 同じ情報は原則一か所。選択肢名等の操作に不可欠なラベルは維持し、内部状態の重複説明は削除する。
**Rationale**: latest POの全体基準が以前のREADME/CamScreenやキー別mode表示案より優先する。
**Alternatives considered**: settings詳細列・横持ち多段露出説明・キーごとのAUTO/受付/実測併記は不要重複となる。

**Decision**: 実測近似は現epoch・PREVIEWまたはVIDEO_RECORD intent・取得から2000ms以内の表示軸ごとに有効な値だけから標準候補へ表示専用で丸める。fresh窓は単調時刻で判定し、表示中の定期更新で期限超過を「—」へ戻す（現在の再描画tickは500ms）。
**Rationale**: 補償previewの実測を撮影targetとして表示/保存しない。時刻やISO/SS欠落、旧epoch、期限超過を受付値や架空既定値で埋めない。
**Alternatives considered**: accepted pairやcapturetargetによるactual代用、表示丸めのcamera書戻しは不採用。

**Decision**: pending/unknownの影響項目を論理capturetargetとoverrideの差で判定し、共通一か所へ集約する。全snapshot/内部ISO+SS pairのACKは共通診断情報として扱う。
**Rationale**: ISO変更によるpreview補償pairの時間変更やsnapshot送信をWB/MF変更と誤認しない。

**Decision**: detentはdrag距離を蓄積して閾値ごとに候補indexを動かす。端点では幽霊step/残量による誤発火を防ぎ、実index変更時だけ共通select actionと標準軽hapticへ接続する。
**Rationale**: Android標準haptic設定を尊重し、VIBRATE権限や独自振動engineを追加しない。tap/矢印/accessibilityも同じ選択actionを使う。

**Decision**: STILL大白/CINE待機小白/STARTING白/実RECORDING赤。STARTINGの停止操作は維持し、赤いREC成功としない。STOPPING/FINALIZINGは別進捗とし、下captionを完全非描画にする。
**Rationale**: 最新POが旧README赤待機/caption配置に優先する。進捗/保存結果は状態バー、失敗/無効理由はnotice、詳細は診断とaccessibilityへ保持する。

## Data Model / 表示契約

- **PreviewValue**: current epoch、PREVIEW/VIDEO_RECORD intent、receivedAt、now、実測ISO/SS、標準候補、display「≈」または「—」。display onlyでtarget/候補生成/送信へ逆流しない。
- **CaptureTarget**: 最新論理ISO/SS。目盛りの選択値と診断の独立値。preview補償pair/実測とは別。
- **AffectedFields**: logical target/override差分とpending/unknown。WB/MFが変化しなければ通常表示を保つ。全snapshot受付と項目の受付を同一視しない。
- **Detent**: drag残量、現在index、候補数、閾値。端点/同値はaction/hapticなし。
- **Shutter/Status/Notice**: 形状・色・action・accessibility、共通mode、進捗、失敗/無効理由。caption文字列を内部semanticに使っても画面へ描画しない。
- **Diagnostics**: capturetarget/要求/受付履歴/preview補償/実測/保存の独立区分。既存レポートexportを維持。

## Validation / 限定手順

1. freshの現epoch/PREVIEW・VIDEO_RECORD採用/STILL_CAPTURE・UNKNOWN除外/2000ms内外・欠落/未来時刻・候補丸め・設定不変、影響項目pending、detent蓄積/反転/端点/同値をpure testsで検証する。
2. 全RecordingStage/PhotoStage・失敗/権限/熱/未知値、STARTING白/RECORDING赤、caption非描画と停止到達性をpolicyとcallsiteで確認。準備失敗と撮影後失敗は保持capture receiptで区別し、一時singleIssuedを履歴分類に使わない。
3. MF draft適用取消/WB即時、ISO/SS即時・反映ボタンなし、AUTO/reset、Back、overlay/fixed layout/rotationの既存回帰を確認。hapticは標準API/flags 0/権限追加なしを静的照合する。
4. 担当者がunit/build/lint、`git diff --check`と公開scanを実行する。HTML file表示拒否を迂回/再試行せず、参照構造/CSSと実描画を分ける。
5. 独立レビューで表示場所と重複を明示確認し、最新headでcanonical/必須CI/thread 0を再確認する。merge/配布は未承認。
6. 縦/左右横持ち、STILL/CINE、AUTO/MANUAL、pending/失敗/未測定、haptic体感、文字欠け/暗所可読性/重なり、回転録画継続・RAW保存はユーザー実機確認まで未確認。無断camera/mic操作なし。

## Stop Conditions

新camera処理/権限/依存/独自UI・振動基盤やsecurity拒否迂回が必要なら停止して根拠を報告する。STILL/CINE切替外観・細余白は変更しない。RAW現像/RAW-onlyは別Specの責務。
