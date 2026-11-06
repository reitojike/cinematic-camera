# Tasks: A 設定シート

## Setup / Foundation
- [x] T001 指示書とspec/planを報告し、既定504dpを確認（specs/039-settings-design）。
- [x] T002 共通色/書体/寸法をCaptureDesign.ktへ抽出しCaptureScreen/CapturePanelsから参照（FR001）。

## US1 画角を保った設定
- [x] T003 [US1] CaptureLayout.ktの上限504dpとCaptureLayoutTest.ktの期待値を更新（FR002/SC002）。
- [x] T004 [US1] CaptureSettingsSheet.ktにheader/body/footerとセグメントを構成しCaptureScreen.ktへ接続（FR002/003/005）。

## US2 可否と適用中
- [x] T005 [US2] CaptureSettingsSheet.ktで既存guard/非対応/再接続snapshotの表示を保つ（FR004/007/SC003）。

## US3 管理操作
- [x] T006 [US3] CaptureSettingsSheet.ktにリセットDialog、固定診断/リセットを実装（FR006/007）。

## Verification
- [x] T007 diff check、lint/unit/assemble、可能なUI確認を実施しquickstart.mdと照合。
- [ ] T008 公開差分確認、Draft PR/必須CI/レビュー。実機確認は別途未検証として報告。

依存: T001→T002→T003/T004→T005/T006→T007→T008。同じ画面を編集するため実装は逐次。並列可能な検査例はUS1のlayout試験とUS2のguardレビュー、US3のDialog文言チェック。tasks/分析時にextension hookなし。段階的にcompileしてから全体検証する。
