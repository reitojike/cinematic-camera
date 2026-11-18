# ローカル判定 UI（準備段階・製品外）

JSONを新しく選択すると旧データの保存・取消・移動・集計操作を直ちに無効化し、最新選択の読込とSHA計算だけを採用する。
旧リクエストの遅延成功・遅延失敗は現在のdata/key/labelsや状態表示を変更しない。
空選択のchangeも保留読込を無効化する。ネイティブ選択ダイアログの取消でchangeが発火しない場合は選択自体が変わらない。
保存キー `face-review-v1-<JSON SHA>` と既存ラベルは移行・書換えしない。
`node --test review.test.js` はNode VMの合成非同期回帰で、実ブラウザー操作・本人判定の再検証ではない。

既存の私有フォルダーにある旧3方式UIのコピーには、この非同期選択競合の修正が含まれない場合がある。
再利用時は修正済みcommit `693b8883` 以後の `index.html` / `core.js` / `review.js` を一式で確認し、
旧コピーをそのまま使わない。本人のデータ・ラベル・旧コピーは自動更新しない。
ファイルの場所を変えるとブラウザーの保存領域が変わる場合があるため、保存キーが同じでも既存判定の復元を保証しない。
再利用前に別の合成入力で保存・復元を確認し、本人ラベルの移行や再判定は別途判断する。
`minface.html` / `minface-review.js` の2条件UIとは別の修正であり、完了済み30枚の再判定を要求するものではない。

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

## Windows ICC正規化（合成確認・実写未使用）

`normalize_windows.ps1` は **Windows PowerShell 5.1** のWindows標準WIC/WPFを使う。
追加Python画像ライブラリ、画像サービス、ネットワークは使わない。
[Microsoft ColorConvertedBitmap](https://learn.microsoft.com/en-us/dotnet/api/system.windows.media.imaging.colorconvertedbitmap.-ctor)
と[ColorContext](https://learn.microsoft.com/en-us/dotnet/api/system.windows.media.colorcontext)に従い、
埋め込みICCから標準sRGBへ変換する。ICC無し/複数/変換不能は推測せず拒否。
JPEG/PNG 1フレーム、128MiB以下・64MP以下、EXIF8方向を適用して最大4,000,000画素・8192辺へ縮小。
Pbgra32を黒へ合成し、不透明RGBAを3方式共通の入力にする。Android native comparisonの
ImageDecoderとは別の前処理なので、同一画素だと主張せず、結果を混ぜない。

本人が選んだファイルだけを`SelectedFiles`へ渡す。スクリプト自身はライブラリを列挙しない。
入力/出力は固定ローカルディスク・Git外、reparse/UNC/ネットワーク割当ドライブを拒否。
OneDrive等の同期フォルダの非送信を自動保証するものではないため、実行前に非同期の私有フォルダと確認する。原本は読取専用、
出力は新規ディレクトリのみ。ランダムIDと画素SHAを使用し原本名/URIをmanifestへ載せない。
manifestは全件完了後だけ作る。失敗時は部分出力が残り得るため、その束を使用せずローカルで確認する。
実行コードと.NET版を含む前処理revisionを固定し、別revisionの結果は比較表へ混ぜない。
`SamePcApproved`は実際の本人許可を代替しない。

```powershell
# Windows PowerShell 5.1内。パスは本人が選択したGit外のローカルファイル。
.\tools\face-evaluation\review\normalize_windows.ps1 -SelectedFiles @('<選択JPEG>') -OutputDirectory '<新しい私有入力束>' -SamePcApproved
```

合成試験: `powershell.exe -NoProfile -File tools/face-evaluation/review/test_normalize_windows.ps1`
はsRGB恒等変換、Windows標準SWOP CMYKプロファイルからsRGBへの変換、8方向、縮小上限、
ICC付き合成PNG読取、原本hash不変、ICC欠落拒否を確認した。実写のプロファイル・広色域RGBの
色精度・JPEG EXIF全形式の網羅・PNG eXIf・全AVD/ブラウザー導線は別ゲートで未確認。
追加でICC付きJPEGのEXIF6を実エンコードして読取り→2x3変換、CLI→private_manifest検証の接続も合成試験した。

実写開始前の順序: (1)独立レビュー (2)製品担当から共有AVD空き枠を確保
(3)合成入力を同じWindows内AVDの固定app-privateへ渡して3方式実行し正常終了確認
(4)同じWindowsだけに結果を回収して変換 (5)ブラウザーで本人が選択/判定/再起動復元を確認。
file URLを操作ツールが拒否する場合、別surface/localhostへの置換で迂回せず、その操作を未検証と報告する。
ここまで成立してから本人指定の実写で少数枚→30〜50枚へ進む。Pixelへの転送/操作/配布は別の具体承認が必要。

### ICC と EXIF 色空間の区別

入力コンテナの JPEG APP2 ICC_PROFILE 分割列または PNG iCCP を先に検査し、
実際に埋め込まれた単一の ICC を要求する。WIC ColorContexts の件数は ICC の件数ではなく、
EXIF ColorSpace 由来の組み込みプロファイルも含むため、採否には使用しない。
取得した ICC と WIC 候補のプロファイル全バイトが一致した候補だけを色変換に使う。
EXIF ColorSpace 1 / 65535 だけなら拒否し、ICC と併存していれば埋め込み ICC を選ぶ。
JPEG の分割順・欠落・重複、PNG の重複・CRC・zlib/Adler と 16 MiB 上限も検査する。
合成試験は EXIF-only 2条件の拒否、ICC+EXIF 2条件の受理、逆順分割の復元、
欠落・重複・途中切断・PNG CRC 破損の拒否を含む。実写の色精度の合格ではない。
検出器が返す画像境界外の枠は元の座標と番号を保持し、判定・誤検出数から除外しない。
画面に描くときだけ画像範囲へクリップする。完全に画像外の枠も一覧の誤検出チェックに残る。

## 最小顔サイズの別条件診断（#406）

bundled ML Kit の `mode=ACCURATE_MIN005` は ACCURATE / minFaceSize=0.05 を使う。
従来の FAST / ACCURATE は min0.1 のまま。0.05 は診断候補であり採用値ではない。
ID は `mlkit-16.1.7-ACCURATE-min0.05-no-tracking`。Play配信flavorへは追加しない。
PrivateDatasetRun は従来どおり新しい runId を必須とし、既存出力を拒否する。
合成runnerの候補出力は `synthetic-accurate-min005-results.jsonl` で、既存なら拒否する。

同じmanifest/画素/revision、repeats=1のbaselineとcandidateだけを次で比較データへ変換する。

```text
python tools/face-evaluation/review/compare_minface.py --bundle <私有入力束> --baseline <min0.1.jsonl> --candidate <min0.05.jsonl> --output <新規比較.json> --same-windows-host-approved
```

条件入替・重複・別SDK/別min値・欠測・入力不一致・既存出力を拒否する。
FAILED/CANCELLED/NOT_RUNは保持し、成功した顔なしへ置き換えない。
結果は `face-minsize-comparison-v1` / `diagnostic-unscored-only` の別schemaで、
`conditions` に明示した2条件を保持する。従来の3方式HTMLへ読み込ませない。
既存のreview.json・判定・集計を上書きせず、旧mlkit-accurate IDへ偽装しない。
専用 `minface.html` で比較JSONと同じRGBAを選択し、2条件を別々に判定できる。
`minface-core.js` / `minface-review.js` も同じフォルダに保持する。通信・サーバー不要。
保存keyは `face-minsize-review-v1-<比較JSONのSHA>` で、旧labelsを読取・移行しない。
候補min0.05だけを判定して途中保存/集計することもでき、未判定の基準はunscoredとして残す。
集計は `face-minsize-aggregate-v1` / `human-static-minsize-only` と明示2条件名を持ち、
`face-minsize-aggregate.json` として保存する。画像ID・SHA・枠・元ファイル名は含めない。
未判定・失敗・中止・未実行を顔なし成功へ変更せず、差分を自動的に正解・改善と扱わない。
新画面のブラウザー実操作/保存復元は未検証。host coreとconverter接続の合成試験とは区別する。既存写真での調整は独立評価の代替ではない。
写真・枠・個別結果は同じPC内のみ。実写再実行・実機操作の許可をこの手順は与えない。

合成回帰: `python -B -m unittest discover -s tools/face-evaluation/review -p "test_*.py"`
従来HTML core: `node --test tools/face-evaluation/review/core.test.js`
