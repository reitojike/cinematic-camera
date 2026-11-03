# Tasks: v15撮影画面フィードバック修正

**Input**: [spec.md](spec.md)、[plan.md](plan.md)
**Organization**: USごとに実装/機械確認と実機ACを分離する。チェック済みは記述した範囲だけの完了であり、実機合格やmerge許可を表さない。

## Phase 1: Setup

- [x] T001 Issue #47の追加AC・対象外・旧仕様の置換範囲を整理し、標準resolver/setup-plan/setup-tasksで文書を生成する（specs/012-v15-capture-feedback/spec.md、plan.md、tasks.md）。FR-008。

## Phase 2: Foundational

- [x] T002 Constitution・既存sourceの世代/撮影target/即時要求/向き契約を照合し、表示専用・元timestamp・新権限なしの境界を定める（specs/012-v15-capture-feedback/plan.md）。FR-001/002/005/006/007/008。

## Phase 3: User Story 1 - 露出主値 (Priority: P1 / MVP)

**Goal / Independent Test**: AUTOの軸別正常値保持と期限、MANUAL最新targetを独立に確認する。

- [x] T003 [US1] 現epochの正常PREVIEW/VIDEO_RECORD観測を軸別slotへ保持し、元取得時刻から2000ms以内だけ投影する。欠落・不正・別用途・旧世代・古い観測で延命/置換せず、unbind/epoch変更で消去する（app/src/main/java/dev/cinematic/p0/CaptureState.kt、CameraProbe.kt、CaptureScreen.kt）。FR-001。
- [x] T004 [US1] MANUALの最新capturetargetとAUTO近似実測を表示sourceで区別し、RAW固定target/epochを維持して補償ISO・丸めの書戻しを防ぐ（app/src/main/java/dev/cinematic/p0/ExposureValuePresentation.kt、CaptureScreen.kt）。FR-002。
- [x] T005 [US1] 正常→軸欠落/不正/別用途、旧epoch/切断/未来時刻、2000ms境界、target優先順位とRAW回帰を機械確認する（app/src/test/java/dev/cinematic/p0/PreviewExposureSamplesTest.kt、ExposureValuePresentationTest.kt、StillExposureStateTest.kt）。FR-001/002、SC-001。
- [ ] T006 [US1] AUTOちらつき・MANUAL最新target・診断の補償値・RAW固定targetを実機で確認し、Issue #47へ結果を記録する。最終結果の必要時反映先はdocs/P0_REPORT.md。SC-001、SC-005。未実施を解決扱いにしない。

## Phase 4: User Story 2 - シャッター (Priority: P1)

**Goal / Independent Test**: 58/48dpと全録画状態の色/操作を比較する。

- [x] T007 [P] [US2] STILL内円58dp/CINE待機内円48dpと実RECORDINGのみ赤を設定し、outer tap/a11y/停止/復旧を維持する（app/src/main/java/dev/cinematic/p0/CaptureChromeModel.kt、CaptureChrome.kt）。FR-003。
- [x] T008 [US2] 待機/STARTING/RECORDING/STOPPING/保存/失敗の状態表と既存操作領域・読み上げ接続を機械確認する（app/src/test/java/dev/cinematic/p0/CaptureChromeModelTest.kt、CaptureChromeRegressionTest.kt）。FR-003、SC-002。
- [ ] T009 [US2] STILL/CINE内円と実録画の視認性・停止/復旧を実機確認し、Issue #47へ記録する。最終結果の必要時反映先はdocs/P0_REPORT.md。SC-002、SC-005。

## Phase 5: User Story 3 - 共通目盛り (Priority: P1)

**Goal / Independent Test**: 5項目の入力経路とhapticを比較し、EV/WB即時busyとMF適用/取消を独立確認する。

- [x] T010 [P] [US3] 共通距離蓄積detent/選択action/同期gateをEV/WB/MFへ接続し、実段変更・選択受付時だけ標準haptic(flags 0)を要求する（app/src/main/java/dev/cinematic/p0/ControlScale.kt、ExposureControls.kt、SensorControlEditors.kt）。FR-004。
- [x] T011 [US3] EVは絶対index選択で送信し、WBとともに既存single outstandingのbusy guardを維持する。MFは100等分UI候補に編集中のexact anchorを保ち、Floatの正確な値をdraftへ渡して適用/取消を維持する（app/src/main/java/dev/cinematic/p0/CameraProbe.kt、CapturePanels.kt、SensorControlEditors.kt、SensorControlPresentation.kt）。FR-005。
- [x] T012 [US3] drag/tap/矢印/a11yの共通経路、端点/同値/無効/busyの無触覚、EV絶対選択、MF exact anchor/表示丸め非書戻しを機械確認する（app/src/test/java/dev/cinematic/p0/ControlScaleTest.kt、SensorControlPresentationTest.kt、ProbeControlsTest.kt）。FR-004/005、SC-003。
- [ ] T013 [US3] EV/WBの即時busy・MF適用/取消・5項目の体感と端点/無効時の無触覚を実機確認し、Issue #47へ記録する。最終結果の必要時反映先はdocs/P0_REPORT.md。SC-003、SC-005。

## Phase 6: User Story 4 - Android回転設定 (Priority: P1)

**Goal / Independent Test**: OFF/ON×4方向と設定途中切替/復帰を照合し、UIと出力方向を分離する。

- [x] T014 [P] [US4] 標準自動回転設定の読取/前面observer/復帰再読取を追加し、UIだけOFF縦/ON物理へ投影する。observerの登録解除と4方向最短回転を維持する（app/src/main/java/dev/cinematic/p0/MainActivity.kt、CaptureOrientation.kt）。FR-006。
- [x] T015 [US4] physical出力とUI投影を分離し、録画開始出力freezeと持ち替え時の録画継続を機械確認する（app/src/main/java/dev/cinematic/p0/MainActivity.kt、CameraProbe.kt、CaptureOrientation.kt、app/src/test/java/dev/cinematic/p0/CaptureOrientationTest.kt）。FR-007、SC-004。
- [ ] T016 [US4] OFF/ON×0/90/180/270度、途中toggle、背景化/復帰、撮影出力方向、録画1本と開始方向固定を実機確認し、Issue #47へ記録する。最終結果の必要時反映先はdocs/P0_REPORT.md。FR-006/007、SC-004/005。

## Phase 7: Polish & Cross-Cutting Concerns

- [x] T017 Spec009主値とSpec003 UI回転へSpec012の明示置換リンクを最小追記し、spec/plan/tasksをread-only analyzeで照合する（specs/009-capture-ui-states/spec.md、specs/003-capture-ui/spec.md、specs/012-v15-capture-feedback/tasks.md）。FR-008。
- [x] T018 通常unit/lint/assemble・diffcheck・秘密/個人情報/素材除外・既存RAW/保存回帰を最新差分で確認し、実結果だけを証拠として扱う（docs/BUILD_VALIDATION.md、docs/PUBLICATION_REVIEW.md、.github/workflows/android.yml）。FR-007/008、SC-005。
- [ ] T019 最終headの必須CI・独立レビュー明示OK・canonical review・未解決thread 0を確認し、Draft PRで提示する（docs/OPERATIONS.md）。FR-008、SC-005。merge/Releaseは別承認まで実行しない。

## Dependencies & Execution Order

T001→T002→各US実装→各US機械確認→T018→T019。T017は文書確定後。各実機AC T006/T009/T013/T016は対応機械確認後に別途実施し、未確認のまま残せる。US1〜4は独立に受入可能。CameraProbe/MainActivityなど共有ファイルの編集は直列化する。

## Parallel Examples / Implementation Strategy

US1ではPreviewExposureSamplesTestと表示テストの設計、US2では状態表の照合、US3ではControlScaleTestとMF値テスト、US4ではCaptureOrientationTestの行列整理を、それぞれ実装読取と並行できる。US間の[P]は別ファイル担当時のみ。まずUS1をMVPとして正常保持/期限/targetを確認し、US2→US3→US4を小差分で統合する。ビルドや静的レビューを実機の6不具合解消に数えず、日々の進捗はIssue/PRコメントへ置く。