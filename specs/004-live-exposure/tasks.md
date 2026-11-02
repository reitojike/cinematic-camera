# Tasks: 即時露出
## Setup
- [x] T001 specs/004-live-exposure/spec.md でIssue37と003上書きを定義。
## Foundation
- [x] T002 app/src/main/java/dev/cinematic/p0/ExposureStops.kt に段階・合法range・pairを定義（FR-002/003）。
- [x] T003 app/src/main/java/dev/cinematic/p0/ControlOperation.kt にwatchdog/最新queueを定義（FR-004）。
## US1
- [x] T004 [US1] app/src/main/java/dev/cinematic/p0/CameraProbe.kt と CaptureScreen.kt に即時pair/queue/段階表示を接続（FR-001/003/005/006）。
- [x] T005 [US1] app/src/test/java/dev/cinematic/p0/ExposureStopsTest.kt で名目値/端/fps/seedを検証（SC-001）。
## US2
- [x] T006 [US2] app/src/main/java/dev/cinematic/p0/CameraProbe.kt にtoken/期限/古いchain無効化/背景化とAUTO復旧を接続（FR-004/005）。
- [x] T007 [US2] app/src/test/java/dev/cinematic/p0/ControlOperationTest.kt で最新100件/timeout/lateack/長→短/破棄を検証（SC-002/003）。
## Validation
- [x] T008 docs/BUILD_VALIDATION.md にbuild/unit/lint結果と未実機を記録（SC-004）。最終headレビュー/CIはPR。
- [ ] T009 specs/004-live-exposure/quickstart.md の実機反応・端値・AUTO・回帰を確認（未実機）。
## Dependencies / strategy
T002/T003は別fileで並行可能、T004/T006はCameraProbe共通なので直列。US1は合法段階、US2は有限待ちとして独立検証する。新機能を増やさずDraft PRまで。

## Phase 2: Convergence
- [x] T010 app/src/main/java/dev/cinematic/p0/ControlOperation.kt とCameraProbe.ktでUNKNOWN後の同値再送と同camera再bindの期限hint保持を修正（FR-007、partial）。
- [x] T011 app/src/test/java/dev/cinematic/p0/ControlOperationTest.ktで15s timeout→旧pair、旧epoch/不明時刻/queued重複、camera別期限とclear受付を回帰検証（FR-007、SC-003）。
- [x] T012 docs/BUILD_VALIDATION.mdに最終build/unit/lint結果を記録。実機T009は未完了のまま（SC-004）。
