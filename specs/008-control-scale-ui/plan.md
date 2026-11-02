# Implementation Plan: 撮影コントロールの選択スケール

**Branch**: `feat/011-control-scale-ui` | **Date**: 2026-11-02 | **Spec**: [spec.md](spec.md)

## Summary

ISO/SSのSliderを現在値中央の選択スケールに置換し、既存selectExposureへ即時委譲する。ISO/SHUTTERの2キーだけ採用稿の文字階層・タグ・背景に合わせ、他キーとルーラーは現行を維持する。

## Technical Context

- Kotlin / Android API 28+、既存Compose Material3/Foundation、CameraX 1.6.2を維持。
- 新依存、ストレージ、権限、外部通信なし。JUnit4の純粋モデルテストと既存layout/stateテスト、lint/assembleを実行。
- 1画面限定。プレビュー/レール寸法固定、UI要素だけ回転。カメラ制御実装を変更しない。

## Constitution Check

- Issue #45とspecに範囲/ACを定義済み。標準ComposeのCanvas、clickable、draggable、semanticsを組み合わせる。独自UI基盤を設けない。
- 要求/受付/実測を分離。buildを実機PASSとしない。秘密/絶対パス/撮影画像を追跡しない。
- API/依存/権限/保存境界の例外なし。設計後も同じ判定。

## Project Structure

- `app/src/main/java/dev/cinematic/p0/ControlScale.kt`: 5候補の純粋モデル。候補indexを安全に射影。
- `app/src/main/java/dev/cinematic/p0/ExposureControls.kt`: 56dpスケールと72dpキーの再利用可能Compose部品。CameraProbeに依存しない。
- `app/src/main/java/dev/cinematic/p0/CaptureScreen.kt`: 既存要求経路を部品に接続。core担当から引継ぎ後に編集。
- `app/src/test/java/dev/cinematic/p0/ControlScaleTest.kt`: 中央・端・空・単一・無効index。
- `specs/008-control-scale-ui/{spec,plan,tasks}.md`: 機能契約とAC対応。

## Research / Design

- Decision: README Final v1を採用し、CamScreen variant D/scrimの48dp見出し+56dp目盛り、48dp矢印、5候補、9dp補助線、3dp選択線をComposeで表す。
- Rationale: HTMLは構造/見せ方参照。反映ボタン・モック時間・失敗は最新合意により移植しない。
- Alternatives: Material Sliderは近隣値と中央選択が読めない。全候補scrollは中央配置/操作中追随が複雑になるため固定3セル+既存連続ドラッグ操作を使う。
- UI contract: labels/index/enabled/rotation/onSelectのみを受け取り、要求・キュー・受付・実測を所有しない。端では空セルを保持して現在値中央を保つ。空/無効indexでは操作不可。
- Model: ControlScaleWindowのprevious/current/nextは元候補indexまたはnull。候補を生成しない。
- CaptureScreen接続はcore変更を通常mergeで受け入れてから実施。

## Quickstart / Validation

1. `./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug --no-daemon` と `git diff --check`。
2. ISO/SSの端/中間/単一/空をunit確認。既存ExposureStops/ControlOperation/CaptureLayout/CaptureStateを回帰確認。
3. 実機でSTILL/CINE×縦/左右横持ち、目盛り開閉、即時選択/最新要求、AUTO、撮影/録画/保存/エラー、BackとAF遮断を確認。未実施は未検証。
4. 参照HTMLを標準CUA browserからfile://で開く操作はURLポリシーにより拒否。回避経路を使わず、ソース寸法照合とGUI実像未確認を区別する。

外部API契約は追加しない。research/model/quickstartは本plan内に統合し、重複文書を増やさない。
