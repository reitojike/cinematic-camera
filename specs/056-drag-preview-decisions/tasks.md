# Tasks

- [x] T001 #220範囲・追加API承認・merge停止を確認し、spec/planを作成。
- [x] T002 既存高速化履歴・open PR・保存側の編集範囲を確認。
- [x] T003 [US1] raw入口の不変decision型、capture/overrideを実装。
- [x] T004 [US1] 未指定raw-bit/顔cut両pass/override/取消の合成テスト。
- [x] T005 [US2] Original24PreviewSessionにexact判定とbounded256資源を接続。
- [x] T006 [US2] photorollに最新draft受付とreleaseのexact採用を接続。
- [x] T007 [US2] session/Composeの連続操作・古い結果拒否・切替/回収テスト。
- [x] T008 [P] [US3] 既存原寸callbackで1024/原寸の合成判定差を測定・報告（修正なし）。
- [x] T009 lint/unit/build/Android合成、diff/秘密除外検査。
- [ ] T010 Draft PR、最終head CI、canonical/独立レビュー、未解決会話を確認。
- [ ] T011 本人のmerge個別許可を待つ（未承認で実行しない）。

依存: T003→T004/T005→T006→T007。T008は別worktreeで並列準備、重い実行は共有ホストで順番を調整。T009後にT010。実機#208は別Issueで未測定。
