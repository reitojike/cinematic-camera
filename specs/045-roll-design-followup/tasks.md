# Tasks: カメラロール表示の追補

[Issue #187](https://github.com/reitojike/cinematic-camera/issues/187)

## Setup / Foundation
- [x] T001 原典・既存041・Constitutionを確認し specs/045-roll-design-followup/spec.md と plan.md を定める。
- [x] T002 044の変更境界を確認し、app/src/main/java/dev/cinematic/p0/ の共有ファイルは今回は変更しないと定める。

## US1 許可状態の案内
- [x] T003 [US1] app/src/androidTest/java/dev/cinematic/p0/photoroll/CameraRollBrowserAndroidTest.kt に全面/選択/混合許可の期待表示を追加する。
- [x] T004 [US1] app/src/main/java/dev/cinematic/p0/photoroll/CameraRollBrowser.kt の許可帯条件・色を補正する。

## US2 空状態の操作
- [x] T005 [US2] app/src/androidTest/java/dev/cinematic/p0/photoroll/CameraRollBrowserAndroidTest.kt に空/読込中、2操作の回数・無効時0回、サイズ別の到達を追加する。
- [x] T006 [US2] app/src/main/java/dev/cinematic/p0/photoroll/CameraRollBrowser.kt に中央空状態を実装し既存callbackを委譲する。

## US3 色の統一
- [x] T007 [US3] app/src/main/java/dev/cinematic/p0/photoroll/CameraRollGrid.kt の待ち/代替表示を共通トークンへ変更する。

## Cross-cutting validation
- [x] T008 app のlint/unit/assemble/debugAndroidTest、git diff --checkを実行し specs/045-roll-design-followup/quickstart.md に保証範囲を記録する。
- [x] T009 Issue #187に対応するDraft PRへ結果を関連付け、最終headのCI/canonical/独立レビュー/未解決会話を確認する。実装commit `8336569` の独立レビューとローカルconvergeは完了。最終head b3c20f0でCI/canonical/独立レビュー成功・未解決0、ATD4/4成功を確認し、PR #189をeb99733へmerge済み。実機・実権限・外部viewer未確認は継続。

## Dependencies / Strategy
T001→T002→US1→US2→US3→T008→T009。CameraRollBrowserの同一ファイルを編集するためUS1/US2は並走しない。044の検証中に本specと合成テスト設計だけ先行できる。合成テスト成功と実機未確認を分ける。
統合根拠: [PR #189](https://github.com/reitojike/cinematic-camera/pull/189)。
