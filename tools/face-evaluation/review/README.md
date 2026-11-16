# ローカル判定 UI（準備段階・製品外）

`index.html` を同じ端末のブラウザーで開く。外部 CDN/解析/通信/サーバーは使用しない。
CSP の `connect-src 'none'` と `default-src 'none'` で外向き接続を許さない。
画像を含まないコードだけを Git に置き、データは Git 外に保持する。

これは人ラベル UI の試作であり、推論機能・Android への配布・実写精度の合格ではない。
Android private runner の JSONL から同じ Windows 内の HTML 用 JSON への変換器を追加した。
自動 adb 転送や画像変換は含まない。Android native comparison の判定保存とは別経路である。
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

## 同じ Windows の AVD → HTML 接続（合成確認段階）

`convert_private.py` は既存 PRIVATE_INPUT の manifest / RGBA と、3方式の完了済み
JSONL を読む。FAST / ACCURATE / YuNet、**repeats=1** に限定し、欠落、順序差、
重複、未対応版、画素/manifest/前処理revision SHA不一致、寸法/枠/時間不正を拒否する。
同じ写真の反復から都合のよい結果を選ばない。FAILED/CANCELLED/NOT_RUNは保持する。
runnerの完了通知が成功したことは実行側で先に確認する（JSONLだけでは正常終了を証明しない）。

```text
python tools/face-evaluation/review/convert_private.py --bundle <Git外の入力束> --results <FAST.jsonl> --results <ACCURATE.jsonl> --results <YuNet.jsonl> --output <Git外の新規review.json> --same-windows-host-approved
```

このフラグは本人の許可に代わるものではない。対象が同じWindows内の承認済みAVDで、
素材と結果がそのWindowsを離れない場合のみ使用する。Pixelから結果を回収する用途に使わない。
入力/出力ともGit外を強制し、既存出力を上書きしない。画像をコピーせず、通信/adbを呼ばず、
ローカルHTMLで読めるJSONだけ作る。このJSONには顔枠があるため公開禁止。

HTMLの「評価データJSON」で生成JSON、「RGBA」で元の同じ入力束を本人が選ぶ。
画素SHA検証後、3方式の枠を切替え、見逃し数と誤検出を人が判定する。
未確認は集計に入れず、顔なしも明示的に確認する。公開可能なのはHTMLの集計ボタンの結果だけ。
変換データの時間はfirst-call/warm別、SDK/adapter別の中央値に分け、混合median_msはnullにする。
画像読込/前処理はこのrunnerの時間に含まれない。実機の初回速度とは扱わない。

今回のhost合成試験は変換と集計の契約を確認するもの。実AVD→回収→ブラウザーのend-to-end、
ブラウザーのfile選択/ラベル保存/復元、実写への推論は未確認。以前のfile URL制限の迂回はしない。
実写前にこの導線の合成実走と独立レビューを完了し、AVD枠を製品担当と調整する。
実機操作・配布・写真の別端末への移動は個別承認が必要。Windowsでの実写正規化は
全素材ICC付きなので、向き/sRGB/4MP条件を明示してから行い、DNGとJPEGを混ぜない。

合成host検証: converter 9件成功（hash/geometry/phase/欠測/重複/失敗状態、
生成JSON→HTML共通coreの検証/集計接続を含む）、HTML core 9件成功。
これらはCLI/JSの試験であり、ブラウザー操作やAVD実走の代替ではない。

独立静的レビューでblocking指摘なし。推奨された2枚のfirst-call/warm順序、行順逆転拒否、非object結果行の拒否を追加して回帰成功。
