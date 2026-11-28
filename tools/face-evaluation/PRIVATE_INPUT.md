# 非公開入力の準備と比較（評価専用、製品外）

任意画像runnerを用意したが、実写精度やPixel性能の合格ではない。写真の利用許諾と本人の
実行承認を先に確認する。SDKの利用・診断ログは許容、写真・顔枠は外部送信しない。
ここで示すファイルはすべてrepo外、画像・顔枠・画像別集計は公開しない。

## 入力の固定

入力は向き適用済み、sRGB、不透明、row-majorの R,G,B,255 の4byte/pixel（`.rgba`）。
評価内ではdecode/EXIF/色変換を行わない。3方式が同じ正規化画素を使い、保存用のAHDや原本は
変更しない。変換方法と版を記述した非公開文書のSHA-256を `revision` とする。
埋め込みJPEGと現像後画素を無自覚に混ぜない。DNG由来と公開写真は条件を分ける。

`manifest.json` の例（数値・hashは例で、実測データではない）:

```json
{"schema":1,"geometry":"upright-srgb-argb8888-v1","revision":"<前処理記録の64桁SHA-256>",
 "samples":[{"id":"evaluation-0123456789abcdef","width":1024,"height":768,
 "sha256":"<RGBAファイルの64桁SHA-256>"}]}
```

ファイル名は `evaluation-<ランダム16桁hex>.rgba` のみ。写真名や人物名をIDにしない。
1束1〜64枚、1画像最大4,194,304画素。manifestは64KiB以下。alphaは全画素255。
host `python private_manifest.py <repo外の束>` はhash/寸法/alpha/IDを検証し、
manifestのhashと枚数だけを出す。これは利用許諾を検証する機能ではない。

## 実行前の確認と配置

この手順は自動実行・実機操作の許可ではない。承認された専用AVD/端末だけを対象とする。
個人写真・Pixelは本人へ具体的な操作と所要時間を示して承認を得てから。

1. 最新のAVD運用手順に従い対象を1台に固定し、`ANDROID_SERIAL`を設定。
2. 評価APKとtest APKをインストール。3flavorは独立applicationId。
3. `adb push` と `run-as org.cinematic.evaluation.<flavor>` 等により、許可済みの束だけを
   target appの `files/evaluation-input/` へ配置。repo・共有写真領域・クラウドにはコピーしない。
   任意パスを引数で開かず、この固定フォルダだけを読む。アプリの追加権限は不要。
4. hostと一致するmanifest SHAを指定し、同じ束でFAST/ACCURATE/MULTIをそれぞれ実行。
   各実行には別のrunIdを使い、結果の上書きを禁止する。

```text
adb -s <予約serial> shell am instrument -w -e datasetRunApproved true -e manifestSha256 <SHA> -e runId <16桁hex> -e repeats 3 -e mode FAST org.cinematic.evaluation.mlkit.test/org.cinematic.evaluation.SyntheticRunner
```

ACCURATEはmodeのみ変更。YuNetはmode MULTI、packageは `org.cinematic.evaluation.yunet.test`。
repeatsは1〜20、既定3。first-callはプロセス内の最初の1呼出しだけで、画像ごとのcoldではない。
bitmap構築/ファイル読込は測定外。SDK、変換、bitmap複製を既存の区間に従って区別する。
結果は `files/evaluation-results-<runId>.jsonl`。終了bundleが成功したかを先に確認し、途中失敗の
部分結果を全量成功と扱わない。raw結果はrepo外へ回収し、入力の3hashと人ラベルを照合する。

入力許可フラグはSDK providerの初期化や通信を遮断するものではない。初回オフラインは別試験。
読み取り前と推論直前にhashを確認し、推論は検証したbytesから作ったbitmapのみを使う。
測定ごとに個人画像のメタデータやSDK例外の本文をログに出さない。

## ラベルと結果

人が画像を見る前に、対象にする顔の定義、完全遮蔽/判別不能/極小顔の除外、IoU閾値、
tuning/held-outの分割を固定する。除外が必要な画像を既存の単純な全顔評価に混ぜない。
公開datasetの注釈は人の確認で補完し、dataset本来の公式スコアとは区別する。

ラベルの各sampleには `input_sha256`、`revision`、`manifest_sha256` を追加する。
runnerの結果と不一致なら集計を拒否する。3detectorの予定表も必須とし、欠測を明示する。
採用は本人が比較を見て判断する。顔測光・肌補正へはまだ接続しない。
