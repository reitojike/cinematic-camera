# PR-C before 取得条件

基準: main `53e68ffc722579138c0f673dbdc9ac08d398044f`。製品コードの変更なし。
`DevelopBeforeScreenshotAndroidTest` は、基準版の実 `PhotoRollDevelopView` を同じ合成画像（600×800の図形）、同じ表示状態、同じ固定 Pixel 9a / API 37 ATD へ渡す。
GPU処理・DNG読み込み・保存owner・FGS・権限要求・状態遷移は動かさない。これらの実機/E2E証拠ではない。

## 再現種別と対応

| after番号 | before画像 | 種別・限界 |
|---|---|---|
| 01 | before01-preparing.png | 実旧画面部品。CHECKING/null保存状態 |
| 02 | before02-ready.png | 実旧画面部品。表示済みのREADY |
| 03 | before03-editing.png | 実旧画面部品。表示済みeditorで編集開始 |
| 04 | before04-saving-paused.png | 実旧画面＋旧overlay/FGS表示をソースから試験再現。保存処理は実行しない |
| 05 | before05-published-paused.png | 実旧画面＋旧preview停止overlay再現 |
| 06 | before06-cancelled.png | 実旧画面＋旧取消/preview停止overlay再現 |
| 07 | before07-save-failed.png | 実旧画面＋旧GPU_FAILED phase表示。詳細失敗説明なし（afterと同じ）。実ドライバ障害の再現ではない |
| 08 | before08-low-memory.png | 実旧画面と旧gpuPhotoRollMemoryProblem。型付き確保失敗を表示入力に使用、メモリ圧迫なし |
| 09 | before09-unconfirmed.png | 実旧画面＋旧復旧/preview停止overlay再現。保存結果不明を実生成しない |
| 10 | before10-hold.png | 実旧画面＋旧復旧/preview停止overlay再現。実ownerなし |
| 11 | before11-rechecking.png | 実旧画面。HOLD+savingを入力。旧版は照合専用表示でなく「保存確認中」 |
| 12 | before12-published-no-toast.png | 同じPUBLISHED状態の実旧画面。旧版に成功toastは存在しない |
| 13 | before13-recovery-dialog.png | 旧GpuPhotoRollEditorのMaterial AlertDialog部分を試験用再現。実owner/処理なし |
| 14 | before14-static-info.png | 旧PhotoRollDevelopViewのINFO AlertDialog部分を試験用再現。動的messageなし |
| 15 | before15-saving-notifications-disabled.png | 旧ForegroundSaveNoticeの保存中/通知未許可描画を試験用再現。OS権限は変更しない |
| 16 | before16-cancelled-other-photo-saving.png | 旧overlayを試験用再現。他写真FGSと取消済み写真を表示入力に使用 |
| 17 | 画像なし | 旧版に汎用失敗カードが存在しない。不正状態の実行・疑似画像を作らない |
| 18 | 画像なし | 同上。無効な戻る操作を備えた新汎用カードの旧版表示なし |

旧overlay/ダイアログの文字・レイアウトは基準の `GpuPhotoRollEditor.kt`、`ForegroundSaveNotice.kt`、`PhotoRollDevelopView.kt` を参照した試験用再現である。after側も合成の表示入力による画面試験であり、前後どちらも実アプリの保存状態を実行したキャプチャとは表現しない。

## 同一入力の注意

- 表示済みeditorは `gpuPresentedEditor(bound, bound.requestedValues, false)` を用いる。01 CHECKINGだけは未表示の `bound.showingDraft()` を用いる。
- 保存ボタンは phase=DISPLAYED、非saving/非editing、既存対象phase、preview停止時の `gpuSaveCanContinueWithoutPreview` 条件で有効にする。
- 調整開始はDISPLAYED・非preview停止・非saving と既存 `canBeginStrengthEdit` 条件。
- gpuReadyはDISPLAYED・非preview停止の表示入力。実GPUセッションは作成しない。
- 再開ボタンは既存 `gpuSaveCanResumePreview` を使用。GPU_FAILEDでは有効にしない。
- 取得予定16枚。`assembleDebugAndroidTest --max-workers=1 --offline --build-cache` は成功（2m38s）。ATD・画像確認は未実施。

## 実行

共有枠を確保後、既存lane-1と共通JDK/SDK/Gradleを指定して、`pixel9aapi37atdDebugAndroidTest`、対象class `dev.cinematic.p0.photoroll.DevelopBeforeScreenshotAndroidTest`、`isolated=true` で実行する。
出力はGMD `additionalTestOutputDir/ui-pr-c-before/`。

## 初回取得の不採用と再取得前確認

初回16枚は親Surfaceの欠落により旧overlay文字色が実画面条件と異なるため、不採用とする。初回commit e68b94a7 の取得成功は画面再現の正しさを保証しない。
修正は旧PhotoRollScreenと同じ CaptureTheme → Surface(fillMaxSize) → Column(fillMaxSize.safeDrawingPadding) → View(weight(1f)) とし、View側でsafeDrawingPaddingを重複しない。テーマ・Density・fontScaleはActivity既定を使用し上書きしない。状態/enabled条件は変更しない。
代表before04だけを beforeCase 引数で取得できる。代表の文字色・背景を独立レビューしてから全量を再取得する。
13/14はAlertDialog構成・sizeを変更せず、dialog root位置/寸法、Density/fontScale、操作を含む文字の画面boundsを追加記録する。13の下端切れは取得範囲/合成描画の限界を調べ、旧実機でも発生すると断定しない。再取得前レビューと実行枠確保は別途必須。
