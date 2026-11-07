# Tasks: 写真別調整保持
- [x] T001 既存#81・コード・open PRの重複監査。
- [x] T002 specs/047-durable-edit-values/spec.md と plan.md の整合を確認。
- [x] T003 [US1] codec/storeとAndroidAtomicJournalStorage接続を実装。
- [x] T004 [US1] 原本検証後restore・close時保存を画面に接続。
- [x] T005 [US2] 不正/未知版/別原本/保存失敗/再生成/取消を試験。
- [x] T006 lint/unit/buildと公開情報検査。
- [ ] T007 最終head独立レビュー・canonical review・CI。公開許可とmerge許可は別途確認。
- [ ] T008 Pixel 9aの12MP縦断受入（実機待ち）。

依存: T001→T002→T003→T004→T005→T006→T007。T008は別の実機証拠で評価する。
