# 作業と確認

- [x] main・既存owner・画面破棄の原因を確認。
- [x] 公式APIを比較し、背景継続用の直接FGSと必要なauthority変更を分離。
- [x] ViewModel/LruCacheでbounded表示だけを保持し、原本/設定/epoch照合を接続。
- [x] lifecycle中断と明示取消の値保持を分離。
- [x] 状態・保存再試行の単体試験、Android compile/lint、独立レビュー。
- [ ] 合成Android上のBitmap複製・trim・Activity再生成の実行試験。
- [ ] Pixelの復帰速度・process death・低メモリ・24MP（今回操作しない）。
- [ ] 背景保存service/明示export leaseの別変更。
- [ ] 公開・PR・CI・マージ（許可未確認、今回実施しない）。

## ローカル検証結果

`testDebugUnitTest`の限定4suiteは34件成功、失敗/skipなし。
PhotoRollPreviewRetentionTest（4）、TrialStrengthEditorTest（11）、
ManualOriginalTrialSaveOwnerTest（17）、ManualTrialJpegSaveOwnerTest（2）。
`lintDebug`と`compileDebugAndroidTestKotlin`も成功。
1 worker、Gradle heap 1536MiBで実行し、他担当AVDは操作していない。
PhotoRollPreviewCacheTestの合成Bitmap試験3件はコンパイル済み・未実行。
ソース/仕様の独立レビューOK、material findingなし。CI/端末/性能の合格とは区別する。
APK assemble・native再ビルドは未実施（画素処理/NDK変更なし）。
