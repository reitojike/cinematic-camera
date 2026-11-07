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
既存unit、lintDebug、assembleDebug、assembleDebugAndroidTest、git diff --check。AVDが利用可能ならisolated=trueでfocused instrumentation。
実写真、実機、24MP性能は試さない。確認手順は既存BUILD_VALIDATIONとtestを参照。

## Project Structure
app/src/main/java/dev/cinematic/p0/ の共有表示部品と photoedit/、photoroll/。
app/src/androidTest/java/dev/cinematic/p0/photoroll/DevelopViewAndroidTest.kt。

モード表示は本人確認済みのFR-006に従い、縮小試験だけAmber、Stage Clearだけ顔保護注意を非表示にする。単体試験と3modeの合成画面で検証する。
