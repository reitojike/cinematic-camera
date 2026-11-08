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
