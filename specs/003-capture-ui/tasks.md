# Tasks: 撮影UI Final v1
**Input**: spec/plan/research/data-model/contracts。自動検証と実機合格を別にする。
## Phase 1: Setup
- [x] T001 採用仕様とIssue #31/#32/#33依存を specs/003-capture-ui/spec.md に定義。
## Phase 2: Foundation
- [x] T002 型付き受付/実測と編集遷移を app/src/main/java/dev/cinematic/p0/CaptureState.kt に追加（FR-001）。
## Phase 3: US1 — 状態とAUTO
- [x] T003 [US1] app/src/main/java/dev/cinematic/p0/CameraProbe.kt で受付と実測を接続し露出AUTOと全resetを分離（FR-001/002）。
- [x] T004 [US1] app/src/test/java/dev/cinematic/p0/CaptureStateTest.kt で旧応答/不一致/draft/保持を検証（SC-001）。
## Phase 4: US2 — 撮影UI
- [x] T005 [US2] app/src/main/java/dev/cinematic/p0/CaptureScreen.kt と MainActivity.kt にレール/ルーラー/設定/診断/Backを実装（FR-003/004/005/006）。
- [x] T006 [US2] app/src/test/java/dev/cinematic/p0/CaptureLayoutTest.kt にFITと狭い画面/insets検証を追加（SC-002）。
## Phase 5: US3 — 回転
- [x] T007 [US3] app/src/main/java/dev/cinematic/p0/CaptureOrientation.kt と AndroidManifest.xml で物理向きと固定配置/撮影方向を実装（FR-007）。
- [x] T008 [US3] app/src/test/java/dev/cinematic/p0/CaptureOrientationTest.kt で境界/短経路/録画固定を検証（SC-003自動部分）。
## Phase 6: Validation
- [x] T009 docs/BUILD_VALIDATION.md にbuild/unit/lint結果と未実機項目を記録（最終head独立/canonical/CIは各PR上で管理、SC-004）。
## Dependencies / parallel examples
US1→US2→US3。各USの純粋モデルテストと既存API調査は並行可能。共有CameraProbeへの編集は直列。
## Implementation strategy
まずUS1を最小PRとして検証し、後続を依存PRへ積む。各PRはDraft、許可前にmerge/配布しない。
## 実機（未確認）
- [ ] T010 [US2] specs/003-capture-ui/quickstart.md の編集/画面/エラーをPixel9aで確認。
- [ ] T011 [US3] specs/003-capture-ui/quickstart.md の4方向録画/ファイル向き/背景復帰をPixel9aで確認。
