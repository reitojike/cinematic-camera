# Tasks: 通常MANUAL撮影targetの暫定1秒上限

**Input**: [spec.md](spec.md)、[plan.md](plan.md)

## Phase 1: Setup

- [x] T001 ユーザー承認と既存#37/#44、constitutionを最小spec/plan/tasksへ反映する（specs/011-one-second-exposure-cap/spec.md）。

## Phase 2: User Story 1 - 上限と候補

- [x] T002 [US1] 通常MANUALの1秒上限を共通policyで定義し、新入力と通常SS候補へ適用する（app/src/main/java/dev/cinematic/p0/NormalExposurePolicy.kt、app/src/main/java/dev/cinematic/p0/ExposureStops.kt、app/src/main/java/dev/cinematic/p0/CameraProbe.kt）。FR-001/002。
- [x] T003 [US1] 1秒境界/1ns超過・最短SS/ISO不変・CINE fps候補不変をunitで検証する（app/src/test/java/dev/cinematic/p0/NormalExposurePolicyTest.kt、app/src/test/java/dev/cinematic/p0/ExposureStopsTest.kt）。SC-001。

## Phase 3: User Story 2 - 旧保存の明示移行

- [x] T004 [US2] 旧長秒を合法標準SSへISO保持で正規化し、bindseed/runtime/復帰で一致させ、新ACK後だけ保存、移行要求を共通noticeと診断へ明示する（app/src/main/java/dev/cinematic/p0/CameraProbe.kt、app/src/main/java/dev/cinematic/p0/CaptureScreen.kt、app/src/main/java/dev/cinematic/p0/CapturePanels.kt）。FR-003/004。
- [x] T005 [US2] 候補なし/端末交差/ISO保持/移行冪等と既存AUTO/reset/resume/receiptのpure境界と呼出接続を検証する（app/src/test/java/dev/cinematic/p0/NormalExposurePolicyTest.kt、app/src/test/java/dev/cinematic/p0/StillResumeIntentTest.kt、app/src/test/java/dev/cinematic/p0/CaptureStateTest.kt）。FR-003/004/005、SC-001/002。

## Phase 4: Final Verification

- [ ] T006 assemble/unit/lint・diffcheck・公開scanと最終head独立/canonical/CI/thread0を確認する（docs/BUILD_VALIDATION.md、docs/OPERATIONS.md）。SC-003。
- [ ] T007 ユーザー実機で移行notice/actual/撮影復帰/RAW回帰を確認後に記録する（docs/P0_REPORT.md）。FR-006、SC-003。未実施を改善/freeze解消にしない。

## Dependencies / Strategy

T001→T002/T004→T003/T005→T006。T007は実機証拠後のみ。policyとcamera接続を小差分で統合し、source/候補/復帰の共通境界を先に検証する。独立ファイルのunit設計とcallsite照合は並行可能。同じCameraProbeの編集は競合させない。
