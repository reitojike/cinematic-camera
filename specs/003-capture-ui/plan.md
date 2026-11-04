# Implementation Plan: 撮影UI Final v1
**Branch**: `feat/003-capture-state` | **Date**: 2026-11-02 | **Spec**: [spec.md](spec.md)

## Summary
Issue #31→#32→#33を依存PRで実装する。CameraProbeの既存epoch/非同期gate/保存処理を再利用し、型付き状態、Compose撮影レール、向き処理を順に追加。
## Technical Context
Kotlin/Compose（既存固定依存）、CameraX 1.6.2、Android28+、対象Pixel9a。端末内のみ、既存CapturePreferences/MediaStore。
JUnit状態遷移/境界テスト、lintDebug/testDebugUnitTest/assembleDebug。実機なしで画質・録画継続合格にはしない。
## Constitution Check
事前/設計後とも適合。標準API、原本保持、Issue起点、独立レビュー、未実測区別。製品UIは今回の明示承認範囲のみ。
## Project Structure
- `app/src/main/java/dev/cinematic/p0/CaptureState.kt`: 型付きreceipt/sample/editor。
- `CameraProbe.kt`: 一貫したsnapshotと露出AUTO。既存キャプチャ/録画gateを維持。
- `CaptureScreen.kt` / `MainActivity.kt`: FITプレビュー、レール/設定/診断とpermission host。
- `CaptureOrientation.kt` / manifest: 標準向き検出、要素回転、非Preview usecaseのtargetRotation。
- `app/src/test/java/dev/cinematic/p0/`: 状態/レイアウト/向きの回帰。
## Complexity Tracking
新モジュール・状態管理フレームワーク・外部デザインruntimeなし。診断文字列はログ用途として残す。
