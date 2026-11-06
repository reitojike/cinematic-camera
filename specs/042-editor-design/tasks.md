# Tasks

## Setup
- [x] T001 Issue #175と指示書Dの既存差分/境界を整理。
- [x] T002 spec/planに要確認3の2案比較と推奨を記載し報告。
- [x] T003 要確認3のPO判断を受けて選択を確定。
## US1
- [x] T004 PhotoRollScreenの状態帯/画像caption/工程/停止理由を既存stateから描画（FR001/002/004）。
## US2
- [x] T005 選択した方式でPhotoEditBar/TrialStrengthEditorPaneのキー列と目盛りを実装（FR003）。
## US3
- [x] T006 保存/取消/比較の見た目を変更し既存guardとcallbackを維持（FR004/005）。
## Validation
- [ ] T007 小画面/拡大文字とmode/phase入力の検証（FR006/SC001–003）。
- [ ] T008 lint/unit/assemble/diffと公開差分確認、Draft PR/CI/review。実機未確認を記載。

T001→T002→T003→T004/T005→T006→T007→T008。同一画面の意味境界を保つため逐次実装。

## Phase 1: Convergence

- [ ] T009 保存不可のアクセシビリティ修正後に合成DevelopViewAndroidTestを再実行し、通常/短辺・拡大文字の表示を確認（FR003/005/006、partial）。RAW担当の単独AVD枠解放後に実行。
