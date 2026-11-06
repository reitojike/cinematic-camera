# 作業と確認

- [x] main・既存owner・画面破棄の原因を確認。
- [x] 公式APIを比較し、背景継続用の直接FGSと必要なauthority変更を分離。
- [x] ViewModel/LruCacheでbounded表示だけを保持し、原本/設定/epoch照合を接続。
- [x] lifecycle中断と明示取消の値保持を分離。
- [x] 状態・保存再試行の単体試験、Android compile/lint、独立レビュー。
- [x] 合成Android上のBitmap複製・trim・Activity再生成の実行試験。
- [ ] Pixelの復帰速度・process death・低メモリ・24MP（今回操作しない）。
- [ ] 背景保存service/明示export leaseの別変更。
- [ ] #81に関連付けたDraft PR・CI・最終headのレビュー（公開許可確認済み）。
- [ ] マージ（別途許可待ち）。

## ローカル検証結果

`testDebugUnitTest`の限定4suiteは34件成功、失敗/skipなし。
PhotoRollPreviewRetentionTest（4）、TrialStrengthEditorTest（11）、
ManualOriginalTrialSaveOwnerTest（17）、ManualTrialJpegSaveOwnerTest（2）。
`lintDebug`と`compileDebugAndroidTestKotlin`も成功。
1 worker、Gradle heap 1536MiBで実行し、他担当AVDは操作していない。
PhotoRollPreviewCacheTestはAPI37/x86_64の専用AVDで4件成功（16.412秒）。
Bitmap複製・容量上限・遅延書込み排除に加え、ActivityScenarioによる再生成で同じViewModelと画素を保持し、
UI_HIDDENで保持、RUNNING_LOWとActivity終了で破棄することを確認した。
終了時の確認は、trim後に画像を再登録して存在を確認してからActivityを終了し、空のcacheによる偽陽性を防いだ。
本体は同一実装の成功済みCI APKのローカル試験用コピーを既存debug鍵で再署名し、試験APKと組み合わせた。
AVDは2 core/1536MiB・合成画素のみ。他担当AVD・Pixel・個人写真は操作していない。
ソース/仕様の独立レビューOK、material findingなし。CI/端末/性能の合格とは区別する。
試験APK assembleと依存するnativeビルドは成功（画素処理/NDKソース変更なし）。
