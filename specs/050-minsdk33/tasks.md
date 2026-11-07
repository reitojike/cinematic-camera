# Tasks: Android 13を最低対応とする
## Setup
- [x] T001 #211と#78/#204、既存mainのminSdkを確認する。
- [x] T002 spec/planの境界と停止条件を照合する。
## User Story 1
- [x] T003 [US1] app/build.gradle.ktsのminSdkを33へ変更する。
- [x] T004 [US1] 既存lint/unit/debug buildとAPK minSdkを確認する。
## Convergence
- [ ] T005 秘密除外/diff検査、Draft PR、CI、canonical/独立レビューまで。最新headの全ゲートが揃うまで待機。
