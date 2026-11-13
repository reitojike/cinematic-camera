# PR-C: 現像画面の before / after

**修正版 before 16枚・after 18枚の取得、比較一覧の再生成と全18状態の目視確認が完了しました。本人の表示承認はまだです。**
初回 after は試験入力と製品条件が一致せず不採用、旧 before fixture `e68b94a7` は Surface 親の欠落により不採用としました。現在の原寸素材・比較一覧は、修正版 before `8d5c04f9` と after `c229f3e4` にすべて差し替え済みです。

[比較一覧1枚](comparison.png) を開くと、状態ごとに **左 before / 右 after / 下に文言変更** を続けて確認できます。
スマホでは縦にスクロールし、文字や操作の細部は必要に応じて拡大してください。原寸は下のリンクから開けます。

## 比較の条件

- before 製品: [`53e68ff`](https://github.com/reitojike/cinematic-camera/tree/53e68ffc722579138c0f673dbdc9ac08d398044f)
- after 製品: [`32fec1f`](https://github.com/reitojike/cinematic-camera/tree/32fec1f3d9414ba1a2dfd0060acfade722fd5867)
- after 修正版 fixture: `c229f3e4`（全量取得済み）。before fixture: `8d5c04f90a5293a499a96671155207a5d2b8daff`（全量取得済み）。
- 実際に取得した PNG の縮小・余白配置だけです。UI を描き足したり、before を想像して作ったりしません。
- 17・18 は新しい汎用問題カードのため **before なし** と明記します。
- 引用する文言は、固定した前後 commit の Kotlin 文字列を照合し、ファイルと行を `comparison-manifest.json` に保存します。下段の「変更点」は説明文であり、ソースの引用とは区別します。

**合成入力による疑似再現で、実フローは未検証です。** 保存・取消・照合・通知許可などの実操作、実写真、実機 GPU 描画の合格証拠ではありません。
13・14 はダイアログ表示 fixture、17・18 は正常な汎用 fallback を直接渡す表示 fixture です。不正メッセージの本番処理や実保存 owner の HOLD 保全を実行した証拠にはしません。

## 原寸画像

| 状態 | before | after |
|---|---|---|
| 01 準備中 | [原寸](before/01-preparing.png) | [原寸](after/01-preparing.png) |
| 02 保存前 | [原寸](before/02-ready.png) | [原寸](after/02-ready.png) |
| 03 調整中 | [原寸](before/03-editing.png) | [原寸](after/03-editing.png) |
| 04 保存中・プレビュー停止 | [原寸](before/04-saving-paused.png) | [原寸](after/04-saving-paused.png) |
| 05 保存済み・再開前 | [原寸](before/05-published-paused.png) | [原寸](after/05-published-paused.png) |
| 06 保存取消 | [原寸](before/06-cancelled.png) | [原寸](after/06-cancelled.png) |
| 07 JPEG保存失敗 | [原寸](before/07-save-failed.png) | [原寸](after/07-save-failed.png) |
| 08 メモリ不足 | [原寸](before/08-low-memory.png) | [原寸](after/08-low-memory.png) |
| 09 保存未確認 | [原寸](before/09-unconfirmed.png) | [原寸](after/09-unconfirmed.png) |
| 10 保存保留 | [原寸](before/10-hold.png) | [原寸](after/10-hold.png) |
| 11 保存結果を照合中 | [原寸](before/11-rechecking.png) | [原寸](after/11-rechecking.png) |
| 12 今回の保存成功 | [原寸](before/12-published-feedback.png) | [原寸](after/12-published-feedback.png) |
| 13 重複リスクの確認 | [原寸](before/13-recovery-dialog.png) | [原寸](after/13-recovery-dialog.png) |
| 14 原本と現像の情報 | [原寸](before/14-static-info.png) | [原寸](after/14-static-info.png) |
| 15 保存中・通知未許可 | [原寸](before/15-saving-notifications-disabled.png) | [原寸](after/15-saving-notifications-disabled.png) |
| 16 取消済み・別写真を保存中 | [原寸](before/16-cancelled-other-photo-saving.png) | [原寸](after/16-cancelled-other-photo-saving.png) |
| 17 汎用問題カード・保存保留 | なし（旧版に該当表示なし） | [原寸](after/17-generic-failure-hold.png) |
| 18 汎用カード・移動不可 | なし（旧版に該当表示なし） | [原寸](after/18-generic-failure-navigation-disabled.png) |

## 再生成手順

素材の取得完了・採用確認を受けてから実行します。before 16枚、after 18枚がすべて必要です。
不足・文言不一致・旧版に17/18が混在した場合は停止します。初回の不採用画像とは別のディレクトリを指定してください。

Python と Pillow、および日本語 TTC/TTF フォントを使います。既存の作業用環境を優先し、必要なら公式 PyPI の Pillow を作業用 venv に固定してインストールします。

```powershell
python docs/ui-review/pr-c/build_comparison.py `
  --before-dir <採用したbefore素材ディレクトリ> `
  --after-dir <採用したafter素材ディレクトリ> `
  --font <日本語TTCまたはTTF> `
  --before-fixture 8d5c04f90a5293a499a96671155207a5d2b8daff `
  --after-fixture c229f3e4 `
  --confirmed-captures
```

出力は `comparison.png`、`comparison-manifest.json`、原寸の `before/`・`after/` コピーです。
一枚の縦長画像で、画面を切り抜かずに全体を収めます。原寸 PNG の内容は変更しません。
manifest には製品・fixture commit、34枚の入力 SHA-256、フォント名/SHA-256、Pillow 版、文言の抽出元、一覧の SHA-256 と寸法を記録します。
同一の入力・フォント・Pillow 版なら同じ画像を生成します。作業者の個人用絶対パスは記録しません。
生成後は画像を実際に開き、16組＋新規2状態、見切れ、文字の欠け、前後の対応を確認してからレビューに供します。

文言と検証範囲の詳細は [PR-C 表示レビュー資料](../../UI_PR_C_REVIEW.md) を参照してください。

## 保全中の旧版試験資料

[before fixture ソース](fixtures/DevelopBeforeScreenshotAndroidTest.kt) と [取得条件の記録](fixtures/UI_PR_C_BEFORE_MANIFEST.md) を保全しています。現コピーは修正版 `8d5c04f9` の取得に使ったソースです。before fixture はローカルcommitのため、存在しない公開commitリンクは作りません。

## 修正版 before の取得記録と13の注意

before は `8d5c04f9` で ATD 全量取得成功（2 tests、failure/error 0、2026-11-13 16:38:50〜52 UTC）。16枚、取得元 SHA256SUMS、13/14 の bounds をバイト一致で保存しました。
前後の代表04で density 2.625 / densityDpi 420 / fontScale 1.0 / 1080×2424 の一致を確認しています。

**旧DialogのATD合成再現では最下操作がroot内で切れる。実機再現未確認。**
13は旧版のDialog構成を変えず取得したものです。「今は保存しない」の文字 bounds は y=1700〜1726、root下端も y=1726 です。切れた部分を描き足したり、旧UIを修正して比較したりしません。
- [13のroot/文字bounds](before/before13-recovery-dialog-bounds.txt)
- [14のroot/文字bounds](before/before14-static-info-bounds.txt)
- [取得元SHA256SUMS](before/SHA256SUMS.txt) は取得時の `before01-...` 形式の名前です。掲載PNGは同じバイト内容で `01-...` に対応付け、12のみ `before12-published-no-toast` → `12-published-feedback` としています。

## 最終取得・確認結果

- before: 2試験成功、failure/error 0、1分6秒。16枚を2026-11-13 16:38:50–52 UTCに取得。
- after: 2試験成功、failure/error 0、1分15秒。18枚を2026-11-13 16:43:43–48 UTCに取得。
- 原寸34枚の独立比較、および生成した一覧の全18状態の目視確認が完了。13の旧Dialogの制限は上記のとおりです。
- after fixture `c229f3e4` のCIは7件すべて成功。これらは本人の表示承認や実機E2E検証の代替ではありません。
- 一覧: 1440×19610 px。SHA-256: `7ca40e6885ee554d7b14e009360b360926ba17d7dc37e50772c90f50097f89cc`。
- PNG34枚は取得元とバイト一致。afterの18件のmetrics、beforeのSHA256SUMS・13/14 boundsも保全し、manifestに記録しています。
