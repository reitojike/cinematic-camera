# DNG表示用入力の最初の接続（#78）

## 仕様と受入境界

ユーザーが選んだ端末内DNGを非UI threadで読み取り、まず縮小した1枚を表示するための入力を作る。
これはplan境界Aの**LibRawによる試験用デコード**。PCと同じPixelプロファイル変換や境界BのR7土台を完了したとは扱わない。
`DngPreviewDecoder.decode(resolver, uri, cancellation, longEdge=1024)`をapp内の呼出窓口とする。

- `content:` URIと既存の一時read grantを使い、`openFileDescriptor(..., "r", ...)`をscope内で閉じる。
  恒久権限・共有保存・端末撮影・原本変更・恒久的DNGコピー・外部送信を追加しない。
- workerで1件だけ受け付ける。同時要求はBUSY、キャンセルはCANCELLEDとし、失敗後の次要求を妨げない。
  呼出側が表示要求の世代とlifecycleを管理し、古い結果を表示しない。ここではqueueや撮影hookを増やさない。
- 最初はseekableな通常ファイルdescriptor、64MiB以下、16MP以下、正方画素・単一フレーム・3色Bayer DNGのみ。
  pipe/クラウドstream・他RAW・X-Trans・Foveon・複数フレーム・camera WB不足は明示的に失敗する。
  DefaultScaleによる非正方画素とFuji斜交配置は現像前に拒否し、geometryの拡張も無効化する。
  拡張子やMIMEだけでDNGと判定しない。破損を示すLibRaw data errorも成功にしない。
- LibRaw half-sizeでセンサーから現像し、向きを適用した後、線形域の面積平均で指定長辺以下へ縮小する。
  上限1024、拡大しない。gamma=1、16bit sRGB/D65出力を65535で除したRGB Float32。
  自動WB・自動明るさは無効。camera WBの不足でLibRawが自動WBへfallbackしないよう先に検証する。
- 結果は`Ready.preliminaryLinearSrgb`。元寸法はhalf-size処理**前**のvisible寸法（向き適用前）。
  出力のwidth/heightは向き適用・縮小後であり、顔マスクや表示は出力寸法へ合わせる。
  エラーは固定コードだけ返し、URI・EXIF・端末識別情報をログへ出さない。

## 標準APIと実装の選択

[ImageDecoder](https://developer.android.com/reference/android/graphics/ImageDecoder)はDNGを扱い、縮小と色空間指定が可能。
ただしRAWのWB、demosaic、profile、tone curveをPCのように制御するAPIではない。
調査した[SkiaのDNG経路](https://github.com/google/skia/blob/main/src/codec/SkRawCodec.cpp)は
PIEXで埋め込みJPEGを選ぶ場合があり、DNG SDKのrenderも最終sRGB・ttByteへ変換する。
したがってtargetColorSpace=LINEAR_SRGBやRGBA_F16に指定しても、失われたscene-linear値や精度は復元できない。
これは公開ソースからの判断であり、Pixel搭載codecの実測ではない。

PC側はrawpy/LibRawを使うため、DNGを独自にparse/demosaicせず、公式LibRaw 0.22.3の限定構成を採る。
CDDL-1.0を選択し、公式未改変アーカイブを`third_party/libraw/`に保持してソース提供をupstreamだけに依存させない。
CMake FetchContentはローカルアーカイブのhashを照合して展開し、ソース改変や未固定branch参照をしない。
COPYRIGHT、CDDL、DCB BSD、Adobe由来部分のMIT通知、NDK r29のtoolchain NOTICEと取得案内をAPK assetsへ含める。
LibRawはJNI共有ライブラリへ**静的**に取り込まれ、NDKのC++ runtimeも静的リンクする。
実ELFのDT_NEEDEDはAndroid提供のlibz/libm/libdl/libc。ソース/リンク/通知/再ビルドは
[保持ソースの案内](../../third_party/libraw/README.md)で追跡する。
依存はNDK 29.0.14206865 / CMake 3.22.1、Android system zlib。
外部JPEG/LCMS/RawSpeed/DNG SDK/X3FTOOLS/OpenMP/追加demosaic packは有効にしない。
arm64-v8a（Pixel）とx86_64（合成検証）のみをビルドする。

## メモリと解放

共有ファイルを直接mmapすると、他processによるtruncateでSIGBUSが起き得る。
そのため64KiBずつpreadして上限64MiBの一時メモリsnapshotを作る。原本FDのoffsetやbytesを変更しない。
LibRawをsnapshotより後に生成して先に破棄し、RAW/中間とsnapshotを解放してからJava RGBを確保する。
途中EOF/読み取り失敗はエラー、短い正のreadは継続し、cancelはread中・LibRaw progress・出力行の境界で確認する。
カーネル途中の即時キャンセルや撮影応答は保証しない。

16MPの場合、RAW16bitが約30.5MiB、half-size RGBA16bitが約30.5MiB、processed RGB16bitが約22.9MiB、
1024角RGB Float32が最大12MiB。これにsnapshot最大64MiB、LibRaw scratch、JNI/Java配列等が加わる。
LibRawのmax_raw_memory_mb=128はdecoder内部の制限であり、**process全体のpeak 128MiB保証ではない**。
小さい出力指定だけでRAWの全解像度unpackが消えるとは扱わない。12MPをFloat32 RGBへそのまま展開しない。
合成12MPの単発実行を先に確認し、実DNG・Pixelの時間/観測peak/GC/撮影応答は別に測る。

## R7・#103・顔との境界

`StageClearLook.renderFromBase`は**R7土台済み**の線形sRGBを要求する。
このdecode直後のRGB、gamma付きBitmap、R7土台済みのRGBを同一と呼ばない。
half-sizeはPCの全解像度AHD→resizeとは異なる。DNG default crop・WB W1（+22mired）、
PixelのAsShotWhiteXY/ForwardMatrix/ProfileLookTable/GainMap/露出正規化をPCと揃える検証は残る。
`tools/raw_baseline/r9_pixel.py`のGoogle Camera HDR+ DNGとアプリのDngCreator DNGも同じと仮定しない。
PCの`r7_final.render`が行うP2/I1/S2/E2/K1等も未適用であり、R7 baseとして黙って渡さない。

先行する画面確認でゼロの顔マスクを使う場合も「顔検出未接続」の試験用表示として扱い、
顔保護済み・PC基準の完成JPEGとして共有公開しない。顔・露出・R7の完成後に公開経路へ接続する。
表示/JPEG化のsRGB転送は出力側で1回だけ行う。保存・台帳や#103の画素処理は変更しない。

## 検証とタスク

`DngPreviewDecoderTest`は端末で生成する非写真の合成Bayer DNGを使う。写真fixtureをGitへ入れない。
原本hash保持、half-size寸法、線形面積平均、向き、非正方DefaultScale、非DNG/WB欠落/過大寸法/過大bytes/pipe/キャンセルの拒否と再実行、
12MP→1024の単発処理を確認する。測る時間はdecode全体、VmHWMはprocess全体の累積high-waterで、
nativeだけのpeakやPixel性能へ読み替えない。

- [x] DI01 標準API/PC decode/#103の入力差、ライセンス/依存、メモリ上限と未確認の境界を定義する。
- [x] DI02 読み取りsnapshot・JNI・限定LibRaw decode・worker/直列/cancelの窓口を実装する。
- [x] DI03 合成DNGのinstrumentation testを実装する。APK buildとruntime実行の成功は別に記録する。
- [x] DI04 最終候補のlint/unit/build・合成native実行・公開除外・独立レビューを確認する。結果はPRまたは引継ぎへ記録。
- [ ] DI05 R7土台・PC互換の色入力とPixel実DNG、表示用/全解像度の端末性能を確認する（T006/T008の残件）。
- [ ] DI06 Draft PRを公開し、最終headの実CI/canonical reviewを確認する。mergeは別承認。

合成用エミュレーターを明示してAPKを入れ、`am instrument`または対象を限定した`connectedDebugAndroidTest`を実行する。
実機へ自動インストールしない。Android CIはnative両ABIとtest APKのbuildまでで、instrumentationの実行とは区別する。
test manifestは先頭にAndroidJUnitRunnerを置き、後続の既存DngHandoffInstrumentationも保持する。
AGPのrunner指定で既存登録を上書きしないことを実APKで確認し、handoff smokeはspec016の隔離opt-inで別途起動する。
Draft PRのCI/canonical/独立レビューとmerge承認は既存運用に従う。未実行・外部操作の拒否は成功にしない。
