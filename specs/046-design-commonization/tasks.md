# Tasks: デザイン共通化
## Setup
- [x] T001 Issue #195とspecs/046-design-commonization/spec.md、plan.mdで境界を固定。
## US1
- [x] T002 [US1] app/src/main/java/dev/cinematic/p0/CaptureDesign.ktへ色を集約し、対象5ファイルの別名と未使用関数を整理。
- [x] T003 [US1] CaptureControlVisuals.ktのoutline/choice描画を設定・ロール・診断へ適用。
- [x] T004 [US1] CaptureChrome.ktのStatusChipをPhotoRollDevelopView.ktへ利用し意味と寸法を維持。
## US2
- [x] T005 [US2] PhotoRollDevelopView.ktの比較キー区切り・無効切替全体alphaを統一。
## US3
- [x] T006 [US3] CaptureLayout.ktへ共通判定を追加しDiagnosticLayout.kt等4か所とunit testを移行。
## Validation / Convergence
- [ ] T007 限定10ケースの前後画像比較は完了。操作回帰6件もATDで完了。撮影・設定・診断の全画面は実機確認待ち。
- [x] T008 specs/042・044・045/tasks.mdと044の対象外記録を証拠に沿って整理。
- [x] T009 appのlint/unit（874件）/assemble/androidTestコンパイルとgit diff --check、公開差分検査（基準eb99733上）。
- [ ] T010 Draft PR、最終head CI/canonical/独立review/thread0とconverge。mergeは未承認として停止。

依存: T001→T002→T003/T004→T005→T006→T007→T008/T009→T010。同一ファイル編集は逐次、レビュー/文書確認は並行可。