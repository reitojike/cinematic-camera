# 実装タスク

- [x] T001 Issue #229と既存specを確認し specs/060-develop-stable-preview/spec.md を定義
- [x] T002 specs/060-develop-stable-preview/plan.md に保持・表示・失敗の境界を設計
- [ ] T003 [US1] PhotoRollScreen.kt と表示保持判定に同一写真の更新中表示を実装し単体確認
- [ ] T004 [US2] PhotoRollDevelopView.kt の状態・通知・情報をoverlay/dialogへ移す
- [ ] T005 [US3] PhotoRollComparisonImage.kt に幅基準・縦スクロール表示を追加
- [ ] T006 [US1] 合成UIで処理中表示・旧要求・失敗・保存不可を確認
- [ ] T007 [US2] 合成UIで通知・情報・状態変更時のviewport固定と読み上げを確認
- [ ] T008 [US3] 合成UIで縦横・大文字・比較の描画と入力回帰を確認
- [ ] T009 lint/unit/assemble・差分検査、Draft PR・CI・canonical/独立レビュー

依存: T001→T002→T003〜T005→T006〜T008→T009。同じファイルを扱うため実装を直列にし、レビューとCIは並行可能。実機確認とマージ承認は未完了である。

## analyze
各USに実装と確認を割当。保存・原本契約を変更しない。画面幅と全体表示の両立は領域内スクロールで定義。未解決の必須仕様なし。
