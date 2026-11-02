# Tasks: 通常STILLの二段階露出プレビュー自動追従

**Input**: [spec.md](spec.md) / [plan.md](plan.md)
**Tests**: Issue #44がpolicy/状態遷移unitを要求するため含める。
**Organization**: US1通常自動追従、US2撮影専有と復帰、US3診断の順。実装チェックと実機ACは分ける。日々の進捗ログはIssue/PRで管理する。

## Phase 1: Setup

- [x] T001 Issue #44とconstitutionを仕様・設計へ反映し、標準template resolver/setup-plan/setup-tasksを使用する（specs/007-normal-exposure-preview/spec.md、specs/007-normal-exposure-preview/plan.md）。

## Phase 2: Foundational

- [x] T002 撮影target revision、preview request/receipt、epoch、撮影snapshot、全format terminalを分離し、「current epoch/revision/requestに合わないcallbackは表示/次の撮影/保存成功へ採用しない」を定義する（app/src/main/java/dev/cinematic/p0/StillExposureState.kt）。FR-003/005/006。

## Phase 3: User Story 1 - 通常自動追従 (P1)

**Goal**: 明示PoCなしで即時編集を二段階previewへ追従する。
**Independent Test**: policy境界/最新要求/初期seedをunitと静的確認し、実機の見え方とは分離する。

- [x] T003 [US1] 二段階境界/ISO上限/無効range/整数丸めと連続要求の最新優先を検証する（app/src/test/java/dev/cinematic/p0/ExposureSimulationPolicyTest.kt、app/src/test/java/dev/cinematic/p0/StillExposureStateTest.kt、app/src/test/java/dev/cinematic/p0/ControlOperationTest.kt）。FR-001/002/003/009、SC-001。
- [x] T004 [US1] 既存policyを通常MANUAL targetへ適用し、pendingは最新1件へ集約、通常編集ごとの全rebindを除去する（app/src/main/java/dev/cinematic/p0/CameraProbe.kt）。FR-001/002/003/004、SC-002。
- [x] T005 [US1] ImageCapture.BuilderのAE/ISO/time/boost固定露出を除去し、必要なPreview seedは安全な補償pairのみ、AUTO/全reset時はseedなし再bindへ切替える（app/src/main/java/dev/cinematic/p0/CameraProbe.kt）。FR-004/007/009、SC-002。
- [x] T006 [US1] 通常STILL即時変更を自動適用し、明示PoC開始に依存させずoverlayと即時設定反映を維持する（app/src/main/java/dev/cinematic/p0/CaptureScreen.kt）。FR-001/008。

## Phase 4: User Story 2 - 固定snapshotで撮影し復帰 (P1)

**Goal**: 補償previewと静止画露出を混同せず安全に保存/復帰する。
**Independent Test**: 準備ACK、snapshot、RAW+JPEG順不同/二重terminal、timeoutと旧callbackを模擬する。

- [x] T007 [US2] 固定snapshot、受付失敗、撮影後timeout、全出力terminal、復帰失敗、明示stop/epoch切断を状態遷移テストする（app/src/test/java/dev/cinematic/p0/StillExposureStateTest.kt、app/src/test/java/dev/cinematic/p0/PhotoCallbackGateTest.kt）。FR-003/005/006/007、SC-001。
- [x] T008 [US2] snapshot固定→runtime撮影pair ACK→takePicture→全format terminal→最新preview ACKの専有phaseを実装し、flash OFF/MINIMIZE_LATENCY/ZSLなしを維持する（app/src/main/java/dev/cinematic/p0/CameraProbe.kt）。FR-005/008。
- [x] T009 [US2] 「CAPTURE_PREPARING/CAPTURING/UNKNOWNでは専有を保持し、既存busy UIで競合編集/連打を止める」を実装し、timeoutだけでpreview上書き/編集再開せずterminalまたは明示stopで解放する（app/src/main/java/dev/cinematic/p0/StillExposureState.kt、app/src/main/java/dev/cinematic/p0/CameraProbe.kt、app/src/main/java/dev/cinematic/p0/CaptureScreen.kt）。FR-006/007。

## Phase 5: User Story 3 - 診断と復帰境界 (P2)

**Goal**: target/受付/実測と待ち理由を区別し、背景/CINEへ古い設定を持ち込まない。
**Independent Test**: intent/epoch別の表示と長秒→短秒/AUTO、背景復帰、旧完了を確認する。

- [x] T010 [US3] 長秒→短秒/AUTO、背景化/復帰、旧epoch/旧request完了、撮影intent実測の純粋境界をunitで検証し、AUTO/背景復帰の標準API callsiteを静的照合する（app/src/test/java/dev/cinematic/p0/StillExposureStateTest.kt、app/src/test/java/dev/cinematic/p0/ControlOperationTest.kt、app/src/test/java/dev/cinematic/p0/PendingBindControlsTest.kt、app/src/main/java/dev/cinematic/p0/CameraProbe.kt）。FR-003/007/008、SC-001。
- [x] T011 [US3] 撮影target/preview要求/受付/実測を区別し、準備/撮影/復帰のセンサー物理待ちとACK/queue/rebind待ちを混同しない診断を追加する（app/src/main/java/dev/cinematic/p0/CameraProbe.kt、app/src/main/java/dev/cinematic/p0/CaptureScreen.kt）。FR-003/007/009。
- [x] T012 [US3] 背景化で先にepochを無効化し、foreground最新targetから復帰、CINE/RAW/MediaStore契約を維持する（app/src/main/java/dev/cinematic/p0/CameraProbe.kt）。FR-007/008/010。

## Phase 6: Polish & Cross-Cutting Concerns

- [x] T013 build/unit/lint、git diff --checkと公開前scanを実行し、ImageCapture static長秒混入なし・編集ごとの全rebindなしを確認する（app/src/main/java/dev/cinematic/p0/CameraProbe.kt、.github/workflows/android.yml、docs/PUBLICATION_REVIEW.md）。SC-001/002/004。
- [ ] T014 修正版限定実機手順と未確認制約を最終実装と照合し、Draft PRの最終head独立/canonicalレビュー・必須CI・未解決thread 0件を確認する（specs/007-normal-exposure-preview/plan.md、docs/OPERATIONS.md）。FR-010、SC-003/004。
- [ ] T015 ユーザー確認後に二段階の見え方、長秒→短秒/AUTO、撮影後復帰、RAW/CINE回帰の実機ACを記録する（docs/P0_REPORT.md）。SC-003。v13追加確認を要求せず、証拠なしに完了/改善保証/#37包括解決へ変更しない。

## Dependencies & Execution Order

T001→T002→US1（T003–T006）→US2（T007–T009）→US3（T010–T012）→T013/T014。T015は修正版ユーザー確認後のみ。US1のpolicy/追従は独立検証可能だが撮影を含む成果の完了にはUS2/US3が必要。

## Parallel Examples

US1: T003のpolicy testと、仕様固定後のT006 UI作業はファイル競合がなく並行可能。US2: T007テスト設計とCameraProbeのT008実装は状態契約確定後に並行可能。US3: T010テストとT011のUI表示作業は表示契約確定後に並行可能。同じStillExposureStateTest/CameraProbe/CaptureScreenを編集するtasksは同時実行しないため個別[P]は付けない。

## Implementation Strategy

US1を最小の追従機能として先に検証し、US2の撮影専有、US3の表示/復帰を順に統合する。機械検証と最終レビューを完了してDraft PRまで進める。実機AC未確認は維持し、merge/配布は個別承認待ち。

## Phase 7: Convergence

- [x] T016 [US2] partial: T007の残るRAW+JPEG全formatの順不同/重複/片方だけ完了・エラーのterminal集約について、撮影専有を早期解放しないことを直接テストする（app/src/main/java/dev/cinematic/p0/CameraProbe.kt、app/src/main/java/dev/cinematic/p0/PhotoCallbackGate.kt、app/src/test/java/dev/cinematic/p0/PhotoCallbackGateTest.kt、app/src/test/java/dev/cinematic/p0/StillExposureStateTest.kt）。FR-005/006、US2/AC2、SC-001。集約済みfilesFinished入力の状態テストだけを全format集約の証明にしない。
- [x] T017 [US3] partial: AUTO seedなし再bind・background stop→foreground bindの標準API callsiteを静的検証し、autoControlSnapshot、PendingBindControlsのkey/epoch/one-shot、StillExposureStateのsuspend/epochとlatest-winsの純粋境界回帰を照合する（app/src/main/java/dev/cinematic/p0/CameraProbe.kt、app/src/main/java/dev/cinematic/p0/CaptureState.kt、app/src/test/java/dev/cinematic/p0/PendingBindControlsTest.kt、app/src/test/java/dev/cinematic/p0/StillExposureStateTest.kt、app/src/test/java/dev/cinematic/p0/ControlOperationTest.kt）。FR-004/007、US1/AC4、US3/AC3、SC-001。新Android mock基盤や実装を写すだけのテストを増やさず、CameraXの実呼出順・実復帰はT015の実機ACへ分離する。
