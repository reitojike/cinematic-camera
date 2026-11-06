# Tasks

## Setup
- [x] T001 specs/040-diagnostics-designへspec/planと情報対応表を作成。
- [x] T002 Aの共有tokensを依存として接続（CaptureDesign.kt）。
## US1
- [x] T003 [US1] CaptureDiagnosticsSheet.ktへheader/summary/tabs/control格子と期限バー（FR001〜003）。
## US2
- [x] T004 [US2] CapturePanels.ktから出力/能力/ログを欠落なく移動（FR003/005）。
## US3
- [x] T005 [US3] CaptureScreen.ktへshell接続、footer/復旧/guard保持（FR004/005）。
## Validation
- [x] T006 lint/unit/build/diff checkと可能なUI確認。
- [ ] T007 B単独Draft PR/CI/review、実機未確認を明示。

依存T001→T002→T003/T004→T005→T006→T007。実装は同一ファイル競合を避け逐次。独立に実施可能な検査はUS1の期限値レビューとUS2の情報対応表照合、US3のguard照合。
