# タスク
- [x] 旧specと現行入力の差分を確認し最新要件を定義する。
- [x] 共通目盛り描画と中央選択を実装する。
- [x] 方向統一・整数入力・基準値吸着/触覚を実装する。
- [x] 単体/Android回帰とlint/buildを完了する。
- [x] 独立レビューで要件・入力境界を確認する。
- [ ] 公開許可・最終CI・canonical review等を満たした場合のみPR/mergeを進める。

## 検証結果
最新base d959a7c（Gradle 9.9.0）で lintDebug / testDebugUnitTest / assembleDebug / assembleDebugAndroidTest 成功。単体887件、失敗0。
共通GMD API37 ATDのEditorRulerParityAndroidTest / DevelopViewAndroidTest / CaptureControlVisualsAndroidTestは3件成功。左ドラッグ増加、1段タップ、整数の支援入力、途中の対象切替と無効入力、既存の編集終了/保存制約と撮影表示を検証。
独立レビューの丸め到達・再ラッチ指摘を修正し、微小移動での両方向離脱を回帰化して最終OK。diff検査成功。
ATDは合成UI/ソフトウェア描画のみ。実機の見た目、触覚の強さ・体感、実写真・RAW処理性能は未検証。新規の実機撮影やAPK公開は行っていない。
