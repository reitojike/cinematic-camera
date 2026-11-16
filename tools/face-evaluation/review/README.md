# ローカル判定 UI（準備段階・製品外）

`index.html` を同じ端末のブラウザーで開く。外部 CDN/解析/通信/サーバーは使用しない。
CSP の `connect-src 'none'` と `default-src 'none'` で外向き接続を許さない。
画像を含まないコードだけを Git に置き、データは Git 外に保持する。

これは人ラベル UI の試作であり、推論機能・Android への配布・実写精度の合格ではない。
今ある Android private runner の JSONL とは別の入力 schema である。変換器、
同一端末の推論から UI へ引き渡す経路、Android でのラベル永続化は未接続。
PC 上の試作に Pixel の写真や結果を転送しない。実機への導入/操作は個別承認後。

## 入力

評価 JSON は `schema: 1` と `samples`（1〜64枚）を持つ。各画像は:

- `id`: `evaluation-` + 16桁の小文字hex。ファイル名/人物名を入れない。
- `sha256`: 同じ向き適用済み・不透明 sRGB RGBA8888 の SHA-256。
- `width`, `height`: 正整数。積は4,194,304以下。
- `results`: `mlkit-fast`, `mlkit-accurate`, `yunet` の3キー。
  - `status`: SUCCESS / FAILED / CANCELLED / NOT_RUN。
  - SUCCESS時は `boxes`（x,y,width,height）と `ms`（非負のSDK区間時間）が必須。

RGBAは `<id>.rgba` を選ぶ。表示前に長さ・SHA・全alpha=255を確認する。
各方式の各枠で誤検出を選び、見逃し数を入力して保存する。
確認していない画像を正解として集計しない。顔なしも明示的に0件で保存する。
同じ写真の反復を枚数に数えない（入力は1画像1行に限定）。

## 保存・集計

ラベルは元 JSON の SHA をキーにブラウザーの localStorage へ保存する。
ストレージ拒否/上限はエラー表示。プライベートモード、ブラウザーデータ消去、
別ファイル/別ブラウザーへの移動で消える可能性がある。正式評価前に再起動復元を実証する。
個別ラベルを外へ書き出すボタンは設けない。

集計JSONは方式別の確認済み/未確認/失敗の枚数、検出/見逃し/誤検出数、
確認済み写真のSDK時間中央値のみ。画像ID、SHA、顔枠、元ファイル名は含まない。
これは手動確認による静止画検出件数であり、既存IoU正解枠評価の代用や
プレビュー速度・製品採用判定ではない。時間のcold/warmの扱いは入力変換器で固定が必要。

## 検証

`node --test tools/face-evaluation/review/core.test.js`

非製品host-only変更。製品GPU/source/Gradle/root設定へ接続しない。
合成データによるbrowser操作検証、独立レビューを経るまで実写評価へ使用しない。
ML Kit bundled / Play配信 / YuNetの実APK容量比較は別途同条件で測定する。
既存の2 ABI/debug容量を製品やPlay配信容量と同一視しない。

## 準備段階の検証結果（2026-11-16）

- Node 24 の合成8試験成功。未判定、失敗/取消/未実施、同一画素重複、
  不正枠、不正ラベル、集計への画像ID/SHA非混入を確認。
- JavaScript構文検査成功。製品の変更なし。
- ローカルHTMLをブラウザーで実行する検証は、操作ツールのURLポリシーが
  fileプロトコルを許可せず未実施。別surface/localhostへの置換で迂回しない。
- 独立レビューによる3指摘（画素重複、状態区分、空の見逃し数）を修正。
- このUIの実写利用・Android配布・採用判断は未実施。
