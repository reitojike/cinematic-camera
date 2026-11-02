# Tasks: 固定pair二段階simulation
## Setup / Foundation
- [x] T001 Issue42とspec/plan/research/data-model/contracts/quickstartを定義。
- [x] T002 [P] ExposureSimulationPolicy.kt と ExposureSimulationPolicyTest.ktで合法範囲/二段階/整数丸め/overflowを実装（FR-002/003、SC-001）。
- [x] T003 [P] ExposureSimulationRun.kt と ExposureSimulationRunTest.ktで専用receipt/intent/epoch/保存後復帰/退出を実装（FR-005/006）。
## US1 固定pair検証
- [x] T004 CameraReport.ktへboost共通100のwritable条件を追加（FR-008）。
- [x] T005 CameraProbe.ktへ固定Builder/runtime snapshot/専用receipt/intent観測を接続（FR-001/003/004/005）。
- [x] T006 CaptureScreen.ktへ明示開始/終了・capture/preview別表示・backend/UI guardを接続（FR-003/006/009）。
## US2 復旧
- [x] T007 CameraProbe.kt と MainActivity.ktで保存と復帰/背景化/foreground/失敗/旧callback/CINE解除を接続（FR-006/007）。
## Validation
- [x] T008 build/unit/lint/diff checkとspec整合を確認しBUILD_VALIDATION.mdへ機械検証を記録（SC-001）。最終head独立/canonical/CIはPR証拠で別管理（SC-002）。
- [ ] T009 quickstart.mdに従い実機で固定pair/初回bind/輝度階調/fps/復帰/RAW動画を確認（SC-003、未実施）。
## Dependencies
T001→T002/T003/T004（別file並列）→T005/T006/T007（root統合）→T008。実機T009は未完了を維持。現在のMVPはSTILL固定pair検証のみ、merge/配布は別承認。
