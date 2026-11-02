# Implementation Plan: 即時露出・応答期限
**Branch**: fix/006-live-exposure | **Date**: 2026-11-02 | **Spec**: [spec.md](spec.md)
## Summary
CameraProbe既存snapshot/receiptを維持し、標準段階・latest queue・操作watchdogの純粋モデルを追加する。CameraX1.6.2標準InteropとAndroid Handlerを使い依存を増やさない。
## Technical Context
Kotlin/Compose既存版、CameraX1.6.2、Android28+、Pixel9a。既存preferences/MediaStore。JUnit fake clock/状態、build/lint、実機手動。
## Constitution Check
事前/設計後とも適合。ユーザー新指示が003のISO/SS適用式を上書きする。binding epochは測定callback用に維持し、独立operation tokenで古いchainのみ無効化。CameraDevice/Session直接制御なし。
## Project Structure
`ExposureStops.kt`: 名目値/合法候補/pair。`ControlOperation.kt`: token/deadline/最新1件queue。`CameraProbe.kt`: Handlerと既存future chain接続。`CaptureScreen.kt`: stepped slider、逆数表示、ISO/SS Apply廃止。`CameraReport.kt`: typed最大frame期間。`app/src/test/java/dev/cinematic/p0/*Test.kt`: 状態と境界。
## Complexity Tracking
1 active +1 queued、最初32msだけ集約（連続入力で延期し続けない）。期限は基礎2秒+前後露光/実測frame周期の最大値3回分。Future.cancelにhardware取消を期待しない。失敗/期限時はUnknown・queue破棄。自動再試行しない。
