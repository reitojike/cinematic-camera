# Tasks

## Setup
- [x] T001 specs/041-roll-designへspec/plan/契約を作成。
## US1
- [x] T002 [US1] CameraRollGrid.ktを日付別3列/2dp/tag/最新枠へ（FR001/002）。
- [x] T003 [US1] CameraRollBrowser.ktへheader/filter/folder/進捗を追加。
## US2
- [x] T004 [US2] access/empty/footer/試験注記をCameraRollBrowser.ktへ（FR003/004）。
## US3
- [x] T005 [US3] PhotoRollScreen.ktの既存callbackを維持して一覧表示のみ委譲（FR005）。
## Validation
- [x] T006 表示変換unit、lint/unit/build/diff check、可能なUI確認。
- [x] T007 C独立Draft PR/CI/review、実機未検証を明示。

依存T001→T002/T003→T004→T005→T006→T007。実装は逐次。独立検査例はUS1の日付unit、US2の文言、US3のguardレビュー。

統合の根拠: [最終headのCI・レビュー・merge確認](https://github.com/reitojike/cinematic-camera/pull/179#issuecomment-6690672281)。実機・実写真の未確認はこの完了状態に含めない。
