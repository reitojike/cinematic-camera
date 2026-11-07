# Implementation Plan: 現像デザインの差分修正

Branch: `fix/editor-design-followup` / [spec](spec.md) / [Issue #186](https://github.com/reitojike/cinematic-camera/issues/186)。
Issueはローカル実装後に公開許可を得て起票した。

## Summary / Technical Context
Kotlin / 既存Compose・Material3。追加依存なし、CameraX 1.6.2を維持。
PhotoRollDevelopView、TrialStrengthEditorPane、PhotoEditBarの表示と最小共通部品だけを変更。

## Constitution Check
事前・設計後とも適合。原本、保存、入力契約を変更しない。実機評価と合成Android評価を分離する。

## Research / Design
spec042採用済みのPhotoEditBar視覚変更を継続。ExposureSelectionScaleは離散入力のため置換しない。
CaptureControlKeyの選択背景・上線・右区切りを入力非依存の共通surfaceへ抽出し、現像の追加表示行を保持する。
CaptureRulerHeadingを現像でも利用し中央寄せを共有する。
CaptureModeSwitchの描画をラベル/選択/回転の引数を取る共通部品へ抽出。撮影側のtoggleableと現像側の選択callbackは各ownerに残す。
PhotoEditBarは表示色だけ追加引数で受け取り、gesture/key/semantics処理は無変更。
状態帯の中央Rowだけ横スクロール。戻る・情報は固定。
新規データモデル・外部APIなし。researchとデータ設計は本planへ統合。

## Validation
撮影の共通部品は縦・横回転で選択semanticsとcaller-owned toggleを合成fixtureで確認する。現像の表示/比較は同じ側を再度押しても反転しないことを検証する。
既存DevelopViewAndroidTestに長い名前の固定情報、選択状態・編集終了、目盛り順を追加。合成bitmapのみ。
既存unit、lintDebug、assembleDebug、assembleDebugAndroidTest、git diff --check。
自動instrumentationは[共通GMD手順](../../docs/MANAGED_DEVICES.md)に従い、ホスト実行枠と共通環境を確認してから
`pixel9aapi37atdDebugAndroidTest` を `--rerun`、`isolated=true`、対象classを
`dev.cinematic.p0.photoroll.DevelopViewAndroidTest,dev.cinematic.p0.photoroll.CaptureControlVisualsAndroidTest` に限定して実行する。
API 37 ATDでActivity再生成後もfixtureが維持されることを確認する。画面保存は現在のWindowのViewツリーを
ARGB_8888のソフトウェアCanvasに描く。入力画像も合成ARGB_8888に限定し、HARDWARE bitmapを持ち込まない。
これは表示合成・GPU経路の証拠ではなく、hardware rendering有効端末や実機での成功を意味しない。
タスク専用の手動AVDは起動・新規作成しない。
実写真、実機、24MP性能は試さない。確認手順は既存BUILD_VALIDATIONとtestを参照。

## Project Structure
app/src/main/java/dev/cinematic/p0/ の共有表示部品と photoedit/、photoroll/。
app/src/androidTest/java/dev/cinematic/p0/photoroll/DevelopViewAndroidTest.kt。

モード表示は本人確認済みのFR-006に従い、縮小試験だけAmber、Stage Clearだけ顔保護注意を非表示にする。単体試験と3modeの合成画面で検証する。
