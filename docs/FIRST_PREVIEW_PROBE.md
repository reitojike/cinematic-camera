# 初回プレビューのデコード比較

製品へ接続しない合成入力専用の検証。半解像度デコードを正確な編集経路として採用したことを意味しない。仕様・検証結果・採否は [Issue #233](https://github.com/reitojike/cinematic-camera/issues/233) で管理する。

## 実行

[共有GMD運用](MANAGED_DEVICES.md)に従い既存ATD枠を単独で使用する。実機・個人写真は使用しない。

```text
./gradlew :app:pixel9aapi37atdDebugAndroidTest --rerun --no-daemon --max-workers=1 -Pandroid.testInstrumentationRunnerArguments.class=dev.cinematic.p0.raw.FirstPreviewDecodeProbeTest -Pandroid.testInstrumentationRunnerArguments.firstPreviewProbe=true
```

Windowsはgradlew.batに置き換える。通常は2048×1536、向き1/6、滑らかなグラデーション/高周波の合成入力を比較する。12MP（4000×3000）も必要な場合だけ `-Pandroid.testInstrumentationRunnerArguments.firstPreview12Mp=true` を追加する。既定では最初の明示フラグがない限りskipする。

## 読み方と限界

- `FirstPreviewProbe` のログは既存half-sizeデコードと原寸RGB16→1024pxの時間、8bit sRGB換算最大差、差がある画素の割合を出す。JPEG圧縮差ではない。
- 各入力で正順/逆順の2回のみ。専用warm-up・熱条件統制がない探索比較であり、速度の採否には使わない。エミュレーターの値をPixelへ外挿しない。
- 時間に合成入力作成・ハッシュ照合・一時ファイル削除を含めない。製品側の原本照合・R7・Look・表示も含まないため、初回表示1〜2秒の達成を示さない。
- `outputBytes` は1枚のFloat RGBの既知データ量、`originalRasterBytes` は原寸RGB16ファイルの大きさ。実メモリ使用量や観測最大ではない。LibRaw内部・複製配列・GCの費用は別途測る。
- 原本ハッシュ不変、寸法、半解像度の事前取消後の再試行と既知の一時ファイル削除を確認する。途中取消・原寸側取消、埋込み画像・crop入り入力・色プロファイル・全撮影機種の互換性は対象外。
- 半解像度はデモザイクとcrop契約が原寸側と異なり得る。差を計測するだけで、golden・保存・製品プレビューを変更しない。

## 初回案内像の設計比較（未採用）

設計の正本は [#233 の設計更新](https://github.com/reitojike/cinematic-camera/issues/233#issuecomment-6731426123)、抽出器の契約は [#241](https://github.com/reitojike/cinematic-camera/issues/241)。以下は実装前の照合表であり、別の抽出器・controllerを作る計画ではない。
[#356](https://github.com/reitojike/cinematic-camera/pull/356) に従い24MP/SIGMA fpを優先し、今回は設計だけ。初回表示の実装は24MPと資源整理の後とし、#260の同時メモリ観測、#357の取消後受付、D0のowner整理へ接続する。

| 優先候補 | 現行コードとの接点 | 採用条件・不足する証拠 |
|---|---|---|
| 1 編集済み表示cache | `PhotoRollPreviewCache` / `PhotoRollPreviewKey`。表示ペアの8MiB上限 | 原本bytes/hash/grant・全編集値・canonical recipe/処理版・寸法/向き/crop/色空間を照合。現在のcache-hitで正確な表示ができるなら粗表示を挟まない。pixelsの読取りと保存準備を分離する設計が必要 |
| 2 保存JPEG | `EncodedJpegSnapshot` / `JpegOutputDescription.MAX_JPEG_BYTES`（32MiB） | PUBLISHED記録、source/recipe/output fingerprint、現在の行・権限と同じsnapshotのbytes/hashを照合。各辺8192/24MP以下という設計上限から長辺1024以下へsample decode。prepare/advance/publisherを起動しない |
| 3 埋込JPEG | #241のLibRaw 0.22.3共通抽出設計。製品JNIは未接続 | カメラ仕上げの案内像のみ。RAW unpack/processなし。入力64MiB、符号化8MiB、各辺4096/4MPという候補上限。snapshot自身を原本と照合し、なし/非対応/破損は型付き結果で返す |
| 4 半解像度RAW | `dng_decoder.cpp` の既存 `half_size=1` | 正確な経路は原寸decode→縮小。halfはRAW unpack費用が残り、既存比較でも画素差あり。既存halfにはoriginal側の `applyCrop` がなく、そのまま接続しない |
| 5 正確な結果待ち | 現行placeholderと1024px preview | 候補不在は失敗ではない。正確な結果を粗表示のために遅らせない |

推奨はこの順序を保持し、任意候補を省略できる設計にすること。保存編集値が既定値と同じでも復元・照合は省略しない。原本不一致・権限喪失・破損recipeを「候補なし」として既定編集へ戻さず、既存の停止/再選択を維持する。
24MP/fp入力が #241 の64MiB候補上限を超えても、抽出上限を暗黙に緩めない。粗候補を使わない判断と、正確な入力経路の対応可否を分ける。

### 座標・色・表示の切替

- 埋込の選択thumb向きとJPEG EXIFの出典を保持し、向き1〜8を一度だけ適用する。不明/競合は採用しない。RAW DefaultCropを埋込像へ再適用せず、縦横比一致だけで画角一致としない。
- halfは厳密geometry/cropを既存original検査から共有する候補。`OriginalRasterSink`を直接渡すと原寸mode/原寸出力へ変わるため、geometryと出力modeを分離するまで接続しない。非零/端数cropとmirrorを含む全向きが未検証。
- 標準[BitmapFactoryのbounds確認とsample decode](https://developer.android.com/topic/performance/graphics/load-bitmap)を使う候補。原寸Bitmapを作らず、ICC/入力色空間を確認してsRGB表示へ変換する。設定を指定しただけで実際の色空間・確保量が保証されたとは扱わない。
- 同じFit領域で「編集を反映した画像・確認中」「撮影時の画像・現像中」「簡易表示・現像中」を区別する。粗画像から比較/編集/保存や自動判定を有効化しない。
- 即時切替を基準候補とし、約180ms crossfade、約80msずつの背景経由fadeは本人比較用の未採用値。exact先着ならcoarseを棄却し、一度exactを採用した世代をcoarseへ戻さない。読み上げ・アニメーション無効時の即時切替も必要。ドラッグ中256px経路へ追加遅延を持ち込まない。

### 取消・メモリと実装前の判断

選択世代・入力ticket・編集revisionを結果とfade callbackまで照合する。切替/取消/背景化後の遅い結果は表示しない。取消要求でownerを即解放したことにせず、opaque native完了まで既存資源枠を保持する。撮影/保存/exactが動いていればhalfを開始しない。
抽出snapshotも排他に含め、native解放後に表示decodeへ進む。撮影後の先行準備は単一・低優先・有界候補であり、表示cacheを自動判定値/原寸配列の保管先へ拡張しない。

1024角ARGB8888の2枚で約8MiBは算術上の画素量だけ。#241の主要抽出buffer最大80MiB、保存JPEG snapshotと内部copy、既存before/after/cache、退場中画像、回転/色変換先、LibRaw内部確保を別々の寿命で予算化する。これらを無条件に足して実測peakにしない。
fadeを省くだけで総量が収まるとは限らず、予算不足なら候補自体を省く。総予算・同時owner上限は#260の結果と整合させて決める。

次の採否判断には、原本差替え/編集不一致/旧journal、同一snapshot、向き1〜8/crop/ICC、不正容量、exact先着、取消後再選択、背景復帰、所有解放の合成検証が必要。
計測区間は原本照合、候補読取/抽出、decode/変換、表示、正確結果までを分ける。既存プローブのデコード時間だけで1〜2秒達成とはしない。
実機での埋込有無・画角・色差・切替感は未確認で、本人確認後に採否を決める。表示変更のマージ許可は本設計から推定しない。

顔検出は[既存の比較方針](../tools/raw_baseline/README.md#android顔情報との比較と採用前判断79設計のみ)に従い、ML Kitを第1候補として精度と負荷を評価する。YuNetとの一致だけを採用指標にしない。撮影時Camera2の補助利用は外部DNGの検出とは分離する。
この方針でも粗表示を顔測光・補正の正本にはせず、検出用入力は正確なdecodeから用意する。顔検出の処理時間・メモリを初回表示の予算へ暗黙に追加せず、既存ownerとの順序を後続で決める。SDK最終採用・通信許可・依存追加は別判断であり、本設計のマージには含まない。
