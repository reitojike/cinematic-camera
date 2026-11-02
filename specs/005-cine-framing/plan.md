# Implementation Plan: CINE画角一致と固定Preview

**Branch**: `fix/007-cine-framing` | **Date**: 2026-11-02 | **Spec**: [spec.md](spec.md)
**Input**: Issue #38 / specs/005-cine-framing/spec.md

## Summary

CINEだけにportrait基準9:16 FILL_CENTER ViewPortを作り、既存SessionConfig照会/判定/bindで同じobjectを共有する。Recorderは16:9、Previewも16:9優先。設定は固定preview上のoverlayへ移動する。003の設定時縮小規則は今回の明示要件で置換する。

## Technical Context

**Language/Version**: Kotlin、既存JVM17 target
**Primary Dependencies**: CameraX 1.6.2、既存Jetpack Compose/Activity/lifecycle
**Storage**: 既存MediaStore/アプリ専用ログ。形式・保存先変更なし
**Testing**: JUnit4 layout/input policy、既存rotation/recording/Back unit、Android Gradle build/lint
**Target Platform**: Android API28+、共有保存は29+
**Project Type**: 単一Android app
**Performance Goals**: 設定開閉でPreview再生成・rebind 0回
**Constraints**: portrait固定。録画busy全期間の出力回転固定。実機操作なし
**Scale/Scope**: Issue #38。露出操作・依存・権限・RAW engineに変更なし

## Constitution Check

Phase0/Phase1ともPASS。公開CameraX APIsを使用し独自engine不要。STILL原本保持。指定/受付/実測/保存の証拠を分離。機械検証と実機ACを混同しない。秘密/実撮影/署名設定追加なし。Draftのまま最終reviewへ渡す。

## Project Structure

- `specs/005-cine-framing/`: spec/plan/research/data-model/contracts/quickstart/tasks
- `app/src/main/java/dev/cinematic/p0/CineSession.kt`: framingとSessionConfig
- `app/src/main/java/dev/cinematic/p0/CameraProbe.kt`: mode別Preview選択、bound診断
- `app/src/main/java/dev/cinematic/p0/CaptureScreen.kt`: 固定previewとoverlay、入力guard
- `app/src/main/java/dev/cinematic/p0/CaptureLayout.kt`: preview寸法とoverlay高さのpolicy
- `app/src/main/java/dev/cinematic/p0/PreviewFocusGesture.kt`: 標準gesture/click接続
- `app/src/main/java/dev/cinematic/p0/PreviewClickPolicy.kt`: touch通知とaccessibilityの区別
- `app/src/test/java/dev/cinematic/p0/`: layout/input/既存回帰

**Structure Decision**: 既存appとpolicyテストへ局所変更。SessionConfig/fps/DR/quality fallbackは維持し、UseCaseGroupへ移行しない。

## Phases

0. research.mdで公開APIと代案を記録。
1. data-model.md/contracts/ui.md/quickstart.mdでgeometry/input/検証境界を定義。
2. tasks.mdをsetup-tasksで生成しspec/plan/tasksをread-only analyze。
3. source/unit testsを実装し、最終機械検証の証拠をdocs/BUILD_VALIDATION.mdへ記録。

## Complexity Tracking

Constitution違反なし。新しいframework/module/serviceなし。
