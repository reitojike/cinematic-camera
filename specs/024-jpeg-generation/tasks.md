# タスク

- [x] T001 Issue #125で担当範囲・受入/停止条件を固定し、既存#118/#124契約を調べる。
- [x] T002 recipe/request/frame/controls/targetと接続契約を実装し、関係Issueへ共有する（FR-001/002/003/007）。
- [x] T003 pure fault testsを先に用意する（SC-002）。
- [x] T004 single-use attempt/resource保持とR7/Look生成を実装する（FR-003/005/006）。
- [x] T005 Android as-shot adapter/row JPEG encoderと合成codecテストを実装する（FR-002/004、SC-001）。
- [x] T006 lint/unit/bothABI app/test APK・隔離AVD・公開除外・diffを確認する（SC-003）。
- [x] T007 spec/plan/tasksと実装を収束確認する。最終head CI/canonical/独立レビュー・通常merge/main CIの運用ゲートと結果はIssue #125/PRで確認する（SC-003）。

Pixel実機・full-res品質・provider/enrollment・UI/自動公開の接続は本タスクで完了とせず、#78/#89/#125の残件へ明記する。

## 顔保護なしprivate試験JPEG（#129）

- [x] T008 TR-001/003/004の固定trial recipe/provider/結果型とentry拒否試験を用意する。
- [x] T009 共通pipelineを再利用し、完成ゲートを維持したtrial entryを実装する。実PFD所有/close ackは制御担当へ統一する（TR-002）。
- [x] T010 合成JVM/Androidでtrial状態・原本保全・実codec/中断・完成回帰を確認する（TR-001〜004）。
- [x] T011 UI/制御へ接続契約・未接続境界を共有し、spec/plan/tasksの収束と公開除外を確認する（TR-005）。
- [x] T012 lint/unit/両ABI APKを確認し、最終CI/canonical/独立レビュー・通常merge/main CIの運用ゲートと結果をPR/Issueへ記録する。
