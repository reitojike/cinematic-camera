# GPU 描画エンジンの契約

[親 Issue #276](https://github.com/reitojike/cinematic-camera/issues/276) §4 と
[契約 Issue #277](https://github.com/reitojike/cinematic-camera/issues/277) PR1 を転記した設計の正本。
現行の API・GLSL・実装設計を記録する。Draft PR中の変更はマージまで提案であり、mainの契約を置き換えない。
進捗・検証結果・採否は Issue / PR に記録する。通常debug/releaseの既定はCPUで、Gate 2承認だけでは変えない。
#291は記録された診断APKの実写確認と本人承認を完了した。#289と#290前段Aを確認後、GPU既定化は別途承認する。
CPU数値差は参考値、GPU基準画像への回帰移行は既定化後の#290後段Bとする（§4.11）。
以下は親Issueの節番号を維持し、実装との差を各節に記す。

## 4. 詳細設計

### 4.1 パッケージ・ファイル構成

初期設計の構成。実装の入口と責務を示し、進捗は対応Issue/PRを参照する。

```
app/src/main/java/dev/cinematic/p0/gpu/
  GlThread.kt              専用スレッドとキュー。GL 呼び出しはすべてこのスレッドで行う
  EglContexts.kt           EGLDisplay、owner単位のコンテキスト、オフスクリーン（1x1 pbuffer）、画面用 EGLSurface
  GlCapabilities.kt        起動時に拡張・上限を検査（§4.3）
  GpuTexture.kt            テクスチャと FBO の所有。close で必ず解放。解放漏れを数える
  ShaderLibrary.kt         assets/gpu/*.glsl の読み込み、#include 解決、#define 注入、プログラムのキャッシュ
  ShaderConstants.kt       Kotlin の V1 定数 → GLSL #define 文字列を生成
  PassGraph.kt             パスの実行（ビューポート・入出力の束縛・uniform）。パスごとの時間計測（任意）
  GpuFence.kt              glFenceSync による完了待ち。取消確認の単位
  resample/GpuResample.kt  Pillow 互換の縮小（BOX）・拡大（BILINEAR/BICUBIC）を GPU パスで行う
  color/                    （GLSL 側のみ。Kotlin なし）
  r7/R7Gpu.kt              R7 の各工程のパス構成
  look/StageClearLookGpu.kt ルックの各工程のパス構成
  analysis/GpuAnalysis.kt  自動露出 p97、局所トーン p50/p92、白に近い画素の割合
  StageClearGpu.kt         R7 + ルック全体の公開入口（§4.2）
  preview/GpuPreviewRenderer.kt  画面への描画、フレームの合流、比較表示
  preview/GpuPreviewSurface.kt   Compose から使う表示面
  export/GpuExportRenderer.kt    帯ごとの書き出し（§4.8）
  diag/GpuStageTap.kt      工程ごとの中間結果を読み戻す（テスト・診断専用）

app/src/main/assets/gpu/
  common/color.glsl        sRGB 変換、OKLab、符号付き立方根、hue・band 関数
  common/resample.glsl     Pillow 重みの計算（BOX/BILINEAR/BICUBIC）
  r7/*.frag                profile, log_luma, tone_apply, foundation, vibrance, crisp, skin, warmth
  look/*.frag              skin_weight, glow_source, glow_compose, diffusion, mist_source, mist_compose,
                           orton_prepare, orton_compose, character, skin_chroma_cap, shoulder
  analysis/*.comp          ヒストグラム、数え上げ（Compute シェーダー）
  display/*.frag           sRGB エンコード、フィット・回転、比較表示

app/src/androidTest/java/dev/cinematic/p0/gpu/   GPU同士・安全契約の検査とCPU差の参考記録（§4.11）
app/src/gpuDiagnostic/java/dev/cinematic/p0/gpu/ 実機計測用の画面・ログ
```

### 4.2 公開 API（Kotlin）

Codex はこの形を変えない。変える必要が出たら、親 Issue で合意してから変える。

```kotlin
package dev.cinematic.p0.gpu

/** 写真1枚ぶんの GPU 側の状態。GlThread 上でのみ生成・使用・解放する。 */
internal interface StageClearGpuPhoto : AutoCloseable {
    val width: Int            // 入力（AS_SHOT）の幅
    val height: Int
    /** 写真を開いたとき一度だけ求める、スライダー値に依存しない解析。 */
    val analysis: GpuPhotoAnalysis
}

internal data class GpuPhotoAnalysis(
    val meteredBaseEv: Double,        // R7PreviewInputs.prepare の externalBaseEv と同じ定義（調整 EV を含まない）
    val centralP97: Float,
)

/** 1フレームの描画条件。値はすべて検証済みの既存型の範囲に従う。 */
internal data class GpuRenderParams(
    val strength: Double,             // 0..1
    val adjustments: PhotoAdjustments // warmth -100..100、exposureEv -2..2（既存型をそのまま使う）
)

internal interface StageClearGpu : AutoCloseable {
    /** AS_SHOT の線形 sRGB（D65）入力をアップロードし、写真ごとの解析を行う。 */
    fun openPhoto(input: LinearSrgbImage, stop: () -> Boolean): StageClearGpuPhoto

    /** プレビュー: 画面用サーフェスへ描画。CPU への読み戻しはしない。 */
    fun renderPreview(photo: StageClearGpuPhoto, params: GpuRenderParams, target: GpuPreviewTarget,
        display: GpuDisplayOptions): GpuFrameStats

    /** 試験・診断専用: 指定の工程の結果を線形 RGB float で読み戻す。 */
    fun renderForTest(photo: StageClearGpuPhoto, params: GpuRenderParams, tap: GpuStageTap.Point): LinearSrgbImage
}

internal data class GpuDisplayOptions(
    val showBefore: Boolean,          // true: AS_SHOT 入力をそのまま sRGB 表示（現行の比較画像と同じ定義）
    val rotationDegrees: Int,         // 0/90/180/270（向きは既存の判定結果を渡すだけ）
)

internal data class GpuFrameStats(val gpuNanos: Long?, val cpuSubmitNanos: Long, val decisions: GpuDecisions)

/** 現行 StageClearDecisions と同じ意味。プレビューでは解像度ごとに GPU で求める。 */
internal data class GpuDecisions(val mistAutoStrength: Double, val faceCutFactor: Double /* 現状は常に 1.0 */)
```

書き出しは §4.8 の `GpuExportRenderer` を使う。

### 4.3 GL 環境・スレッド・メモリ

- **API**: OpenGL ES 3.2（Compute シェーダーを含む）。Android 標準 API であり、新しい製品依存は加えない。host専用テスト依存は §4.11 の承認条件に従う。
- **必須の能力**（`GlCapabilities` で起動時に検査し、満たさなければ GPU 経路を使わない）:
  - `GL_EXT_color_buffer_float`（RGBA32F/R32F へ描画）
  - 最大テクスチャサイズ ≥ 4096
  - Compute シェーダー（ES 3.1 以上）
- **精度**: シェーダーは `precision highp float;`。中間テクスチャは RGBA32F（色）・R32F（平面）を既定とする。RGBA16F は Gate 1 の計測で差が基準内と確認できたものだけに使う。
- **スレッド**: GL の呼び出しはすべて `GlThread` 上。UI スレッドからは要求をキューに積むだけ。要求は**最新の1件だけ**を残して合流する（Choreographer のフレームごとに1回描画）。
- **コンテキスト**: #341の製品GPU接続はpreviewとexportを独立owner/thread/contextにする。保存前にpreview owner終了を確認し、保存中はpreviewを停止する。保存後のpreview再開は明示操作。初期案の共有context・同時描画は採用していない。原本展開と表示の所有期間を重ねず、解放不明なら次ownerを開始しないためである。
- **メモリ**: `GpuTexture` がバイト数を記録し、合計を `GpuMemoryLedger` に報告する。上限はプレビュー 128MiB 以下、書き出し 192MiB 以下（12MP）。超える要求は失敗させる（黙って縮小しない）。台帳は登録されたGPU textureの論理byteであり、CPU入力・Bitmap・driver・PSSや全工程のRAM上限ではない。`peakAllocatedBytes` は成功した予約の最大値を解放後も保持する。AS_SHOT を含む全工程の最大と予算との差を計測する。予算変更の判断と Gate 1 基準は [#276](https://github.com/reitojike/cinematic-camera/issues/276)・[#285](https://github.com/reitojike/cinematic-camera/issues/285) に記録する。
- **解放**: 写真切替・背景化・画面終了と `onTrimMemory >= TRIM_MEMORY_UI_HIDDEN`（20）でpreview ownerを閉じる。trim10はこの契約による解放をせず、foreground exportのownerも閉じない。製品previewのcontext喪失は見える失敗とし、queue済み要求では再生成しない。明示retry/離脱/trimで旧ownerの終了を確認する。失敗直後のGL写真/画面surface解放と、旧owner終了時の縮小CPU入力/thread/EGL解放を区別する。低水準診断の次要求によるcontext再生成契約は製品retry契約と別。

#### 資源再利用と全工程計測モード（#323）

この節の数値・採否待ち表記は当時の候補の履歴。CPU差の現行の扱いは§4.11を優先し、過去の失敗を新条件の成功に書き換えない。

**予算の事後変更**: 利用者は第1部の結果と資源再利用案のMesa結果を見た後、
プレビュー上限を96MiBから128MiB（134,217,728B）へ変更した。資源持越時のpeakが約96MiBに
達し余裕がほぼ無かったことと、Pixel 9aのメモリ量に対して128MiBは無理のない量との判断による。
Gate 1基準④と `GpuMemoryLedger.PREVIEW_BUDGET` を合わせ、①〜③・書き出し192MiBは不変。
旧96MiB実測は履歴として残す。新128MiBでの4形状peak/persistent/余裕を別に記録し、
Mesa/Android一致・解放0・CI/canonicalが揃うまで第2部に含めない。B待ちで第2部を遅らせない。

製品の通常描画 `GpuFrameMode.NORMAL` は #319 と同じく、強さだけを変えたときにR7とGlowの
準備済み結果を再利用する。warmth/EV変更やstage tapでは、借用するGlowを先に解放してからR7を
計算し直す。①計測用の `FULL_RECOMPUTE` は別に設け、stage tapの有無に依存せずR7・Glow・Mist・
Orton・Character・肌保護・shoulder・表示を再計算する。画像・場・R7局所トーン分位点・Mist判定を
前frameから使わず、frame終了時に結果leaseを返す。係数・GL保存領域・programは再利用できる。

写真由来のp97/base EVは両モードとも `openPhoto` で `R7PreviewInputs.prepare` を一度だけ呼ぶ。
これは初回準備で、基準①の計測外。エンジンは再解析のためのCPU写真コピーを保持しない。
rendererがsurface再生成/trim復帰用に保持する元入力は既存どおり。Before/INPUT tapはルックを通さない。
`DefaultStageClearGpu(egl, ledger, GpuFrameMode.FULL_RECOMPUTE)` が標準の明示選択。
独自pipeline factoryでは `ConnectedStageClearGpuPipeline(input, analysis, GpuFrameMode.FULL_RECOMPUTE)`
を渡す。GPU表示診断・DNG試験・一致/メモリ診断はこのモードを明示し、診断画面/計測ログにも表示する。
通常cacheの回帰試験は意図的にNORMALを使う。GPU timerだけで①のwall判定を代用しない。

`GpuTexturePool` は写真所有の同一GL thread/contextに限定し、同寸法・形式で寿命の重ならない
storageとFBOだけを再利用する。毎回新しいleaseを返し、close済みleaseを再び有効にしない。
idle storageも実allocationとして台帳に残し、新規確保が128MiBを超える前に古いidleだけを削除する。
計算結果は新しいproducerが全面上書きする。新context/global/ThreadLocalは導入しない。
公式の [immutable texture storage](https://registry.khronos.org/OpenGL-Refpages/es3/html/glTexStorage2D.xhtml)
を使用する。fragmentは同一contextの命令順序に従い、BOXのimageStoreは再利用先を先行する
texture fetchから守るため、dispatch前に `GL_SHADER_IMAGE_ACCESS_BARRIER_BIT` を入れる。
既存のdispatch後barrierは後続の読取りを保護する。shader式・Float境界・判定許容差は変えない。

R7/Glow/Mist/Finishは写真ごとの `GpuResample` を借用する。`inputSize/outputSize/filter` のtableを
共有し、Mistの最終縦補間も同じtableを借用する。FULL_RECOMPUTEではtable以外の場・結果・判定は持ち越さない。
写真close、trim、現在のsurface喪失、context lossでpoolを解放し、次surfaceでは保持AS_SHOTから作り直す。
新surfaceが既にあるときの古いdetachは新写真を解放しない。

修正版128MiBの4形状・連続5frame（強さ0.5/0.5/1/0/0.5）は、tapなしのNORMALとFULL_RECOMPUTEを
別photo/ledgerで測り、各frame最終出力raw-bit hashが一致。初回後のtexture新規確保0、係数table数不変、
写真解析は各photo1回。NORMALはR7 pass累計5/Glow準備1のまま、FULLは各frameでR7を5pass・Glowを1回追加。
closeは全8組で台帳0。表示encodeを追加するstage tapと、通常のSHOULDER消費は別に記録する。

| 入力寸法 | モード | 台帳peak（bytes） | persistent最大（bytes） | 128MiBへの余裕（bytes） |
|---|---|---:|---:|---:|
| 1024×768 | NORMAL | 100,103,036 | 100,103,036 | 34,114,692 |
| 1024×768 | FULL_RECOMPUTE | 100,103,036 | 100,103,036 | 34,114,692 |
| 768×1024 | NORMAL | 100,103,036 | 100,103,036 | 34,114,692 |
| 768×1024 | FULL_RECOMPUTE | 100,103,036 | 100,103,036 | 34,114,692 |
| 1024×576 | NORMAL | 75,113,780 | 75,113,780 | 59,103,948 |
| 1024×576 | FULL_RECOMPUTE | 75,113,780 | 75,113,780 | 59,103,948 |
| 886×886 | NORMAL | 101,703,136 | 101,703,136 | 32,514,592 |
| 886×886 | FULL_RECOMPUTE | 101,703,136 | 101,703,136 | 32,514,592 |

通常cache対stage tapの32条件もSHOULDER RGBA32F rawbits/判定が一致し、warmth/EV/戻しでcache無効化を確認。
旧c11ec862の128MiB表（103,248,764 / 103,248,764 / 77,473,076 / 104,843,120B）はtapありで
写真解析まで毎frame再計算していた旧候補の履歴。新仕様の受入証拠として再利用しない。

これはMesa上の実Kotlin/GLES経路。Android実rendererのtrim/surface loss試験は別に必要。

#319の44a12b9と変更後の実Kotlin経路をMesa GLES3.2へ接続した200条件で、全13tapの
SHA-256は一致した。旧96MiB時の4形状・連続5frameのMesa予備検証は以下。Androidのdriver/lifecycle、
実機性能、Gate判定を意味しない。最終headのAndroid・CI・reviewが揃うまで採用HOLD。

| 入力寸法 | 台帳peak（bytes） | frame間persistent最大（bytes） | 写真close後 |
|---|---:|---:|---:|
| 1024×768 | 100,586,364 | 100,043,596 | 0 |
| 768×1024 | 100,574,076 | 100,047,692 | 0 |
| 1024×576 | 77,473,076 | 77,473,076 | 0 |
| 886×886 | 100,660,184 | 99,866,896 | 0 |

この履歴の予算96MiB=100,663,296B。persistentには入力・係数・未使用storageを含む。256×192と1024×768の
10fixture×計200条件も予算内。小さい一時形状は予算内でevictされるので確保回数0は保証しない。
速度の改善は実機で別に測る。第2部APK固定時に一致・4形状128MiB予算・解放0・CI/canonicalの4条件が
揃わなければ本変更を待たず、#319の現エンジンで第2部を開始する。

#### 資源再利用前の承認4形状のメモリ観測

#284接続候補87202fc / gpuDiagnosticの指定Androidで、4形状×2調整×2強さの16条件を完走し、
Mesa実shaderのallocation監査と全最大値が一致した。R7_FINAL/Glow/Mistは固定工程基準内、
全写真close後のallocatedBytes/textureCountは0。メモリにはOrton→Character→skin cap→shoulder→DISPLAY8の
RGBA8画像生成まで含む。試験のMIST指定は読み戻す値の選択で、後半を省略しない。全16条件の後半5tap到達をJSONでも確認した。
実Windowのpresent/swapとドライバ内部メモリはこの台帳測定外。
テストのCPU参照保持寿命を切り、192MiB heapのOOMは再発しなかった。
heap、96MiB GPU予算、数式は変更していない。

| 入力寸法 | GPU台帳最大（bytes） | 96MiBへの余裕（bytes） |
|---|---:|---:|
| 1024×768 | 83,094,576 | 17,568,720 |
| 768×1024 | 83,092,528 | 17,570,768 |
| 1024×576 | 62,373,256 | 38,290,040 |
| 886×886 | 82,968,592 | 17,694,704 |

最大はOrton blurの縦拡大出力確保時。AS_SHOT/R7/bloom/MIST/bright/softの原寸RGBA32F6枚、
skin/halationのR32F2枚と1024×62横軸中間が82,804,736B、重みcacheが289,840Bで計83,094,576B。
blur-owned縮小画像は第1拡大軸後に解放済み。[全85テクスチャの名前・寸法・生成工程](https://github.com/reitojike/cinematic-camera/issues/276#issuecomment-6762284391)を記録した。
一覧はshader実行の生成解放イベントを監査したもので、総量をAndroid台帳と照合している。ドライバ内部のメモリ一覧ではない。
超過4形状はGPU allocation前に拒否され、peak0。旧1024×769は別baselineで履歴照合し、
承認入力へ混ぜない。この限定メモリ試験だけを全工程画質・Gate1・実機の合格とは扱わない。

### 4.4 色と座標の約束

- 入力・中間は線形 sRGB（D65）、シーン線形。クリップや転送は Kotlin が行う箇所だけで行う。
- 画素中心は `(x + 0.5, y + 0.5)`。`texelFetch` の整数アドレスと Pillow の明示的な重みを使う。
- 行列の乗加算順序と各軸間の Float 丸めを保つ。Kotlin の見本の計算は変更しない。
- 2026-11-10 の利用者判断（#279 / PR #305）により、`cs_signedCbrt` は pow の seed に precise 付き Newton 法を1回適用する。有限極値で cube が overflow、または分母が0の場合は有限 seed を保持する。0は0を返す。
- 同判断で色相補正 B2 を採用する。元の `atan(b, a)` を seed とし、方向 `(a,b)` を `frexp` の仮数と共通指数の差による `ldexp` で正規化してから sin/cos の残差で1回補正する。正角度へ不要な360加算をせず、負角度だけ正規化する。符号付き両ゼロの規約を維持する。
- MAX 値による除算は指定 Android で両成分が0となったため使わない。GLSL ES §8.3 は `ldexp` の指数>+128を未定義、指数<-126を0へflush可とするので、単純な `ldexp(x,-最大指数)` も使わない。仮数から共通指数との差（非正）で揃えれば支配成分の指数差は0となり、方向を失わない。ゼロ成分の指数0は共通指数の選択から除外し、正の指数差も0で抑える。
- 色相の利用側は円周上の距離を使う。試験も `min(abs(ref-actual) % 360, 360 - abs(ref-actual) % 360)` で比較し、0°と360°を同じ角度とする。線形のULP距離は診断値であり、色相の合否には使わない。

次の値は#279時点のCPU比較の旧判定値。現在は `GpuTolerances` に参考基準として保持し、超過だけで停止・不合格にしない。数式や値を差分消去のために変更しない。finite・寸法・hue範囲等の出力契約は維持する。

| 工程 | 旧CPU比較基準（現在は参考値） |
| --- | --- |
| RGB→Lab、Lab→RGB、および往復 | 参照の絶対値≤1では絶対差≤4e-6、絶対値>1では相対差≤2e-5 |
| 色相 | 円周上の絶対差≤1e-4度 |
| 転送、立方根単体、band | 従来どおり絶対差≤2e-6 / 相対差≤1e-5 |
| skinWeight | 従来どおり絶対差≤1e-5 |
| plane/RGB blur、BICUBIC | #279 の従来基準を維持 |

根拠: [GLSL ES 3.20.8 §4.7.1](https://registry.khronos.org/OpenGL/specs/es/3.2/GLSL_ES_Specification_3.20.html) は、pow の精度要件を `exp2(y * log2(x))` から継承させ、三角関数の精度を同表で定義しない。subnormal の0へのflushも許す。powが必ずその式で実装されるという規定ではなく、Math.cbrt/atan2 と同じ丸めを保証するものでもない。
これらの旧閾値は規格から得られる保証値ではなく、当時の測定と利用者承認に基づく値である。Newton後の往復最大差は Android 2.682209e-6 / Mesa 2.592802e-6、B2後のMesa色相最大差は約3.05176e-5度だった。最終の exact head を両環境で検証して記録する。

**現在の品質判断は§4.11に従う。** 全工程のCPUとの差・旧Orton境界除外は参考記録であり、Gate 2は本人の実写判断とする。GPU帯/full・cache/fresh一致、shader、安全契約を自動検査する。合成結果を実機の画質・速度の合格へ読み替えない。
### 4.5 Pillow 互換のリサンプル（GPU）

`StageClearBlur.resize` と `OriginalField` が真実の源。GPU 版は次の通りに実装する。

1. **重みの計算は CPU（Kotlin）で行う。** `StageClearBlur.samples(inputSize, outputSize, filter)` をそのまま呼び、各出力位置の `first` と正規化済み重みを、R32F のテクスチャ（行=出力位置、列=タップ）と R32I（`first`）にしてアップロードする。重みは画像サイズと半径だけで決まるので、写真ごとにキャッシュする。
2. **軸の順番**は `StageClearBlur.resize` と同じ規則（`verticalFirst` の条件）で決める。軸ごとに1パス。
3. 各パスは「出力1画素 = Σ 入力[first+i] × w[i]」。BOX 縮小は1出力あたりのタップ数が多い（例: 1024px で半径 7% なら約70タップ）ため、**縮小パスは Compute シェーダー**で、行（列）単位にワークグループを割り当てる。各invocationは出力画素を担当し、タップを元の順で単精度累積する。初期案の共有メモリ部分和は使わない。拡大（BILINEAR 2〜3タップ、BICUBIC 4〜5タップ）はフラグメントシェーダー。
4. 軸と軸の間で値を単精度に丸める（Pillow と同じ）。累積は単精度（Kotlinは倍精度）。CPUとの差は参考記録とし、GPU帯/fullの演算順序は一致させる。
5. `plane(input, radius)` = BOX で `reducedSize` へ縮小 → BILINEAR で原寸へ。`rgb()` は**色を分けずに** RGBA テクスチャのまま処理する（Kotlin の色ごとのコピーは不要）。

### 4.6 工程の対応表（パス構成）

すべて Kotlin の該当関数を**1行ずつ写す**。式を簡略化・近似しない。

| 工程 | Kotlin の真実の源 | GPU パス | 備考 |
|---|---|---|---|
| warmth | `PhotoAdjustments.applyWarmth` | 1パス（R7 profile と融合可） | ゲインは Kotlin で計算し uniform |
| R7 profile | `R7Base.profile` | 1パス | |
| R7 局所トーン | `R7Base.smoothLogLuma`・`localTone` | log 輝度 → BOX 縮小（小さな格子）→ BICUBIC 拡大 → 分位点（§4.7）→ 適用 | 拡大は適用パスの中で格子から直接計算してよい（原寸の場を作らない） |
| R7 foundation | `R7Base.foundation` | 1パス | ROI は空（全 0）。製品でも楕円なし |
| R7 vibrance・crisp・skin | 同名関数 | 1パスに融合 | 融合しても式は工程ごとに順に適用 |
| 肌の重み | `StageClearGlow.skinWeight`（`StageClearColor.skinWeight` → 半径 SKIN_RADIUS のぼかし → 0..1） | 1パス＋ぼかし | |
| Glow | `StageClearGlow.addLight`・`renderNonzero` | source → bloom 3半径＋halation のぼかし → 合成 → diffusion のぼかし → 合成 | NORMALは強さ変更で準備結果を再利用。FULL_RECOMPUTEは場も毎frame再計算（#323） |
| Mist 自動強度 | `StageClearMist.nearWhiteFraction`・`autoStrength` | 数え上げ（Compute、§4.7） | プレビューは描画解像度で求める |
| Mist | `StageClearMist.applyMist`・`render` | source → 2半径のぼかし → 合成 | |
| Orton | `StageClearOrton.mixOrton`・`applyOrton`・`render` | encode/bright → ぼかし → mix → 彩度保護 → 強さで混合 | |
| Character | `StageClearCharacter.vignette`・`vibrance` | 1パスに融合 | |
| skinChromaCap | `StageClearProtections.skinChromaCap` | 1パス | 顔マスクは現状すべて 0。uniform で 0 を渡せる形にしておく |
| face cut | `StageClearProtections.faceCut` | Phase 0・1 では実装しない | 製品の顔マスクが NONE のため発生しない。インターフェースだけ用意し、呼ばれたら失敗させる |
| shoulder | `StageClearProtections.shoulder` | 1パス | |
| 表示 | `TrialDisplayPixels.byte` | 1パス（sRGB エンコード、8bit 丸め） | GPU表示/encodeの一致を検査し、CPUの `channel()` との差は参考記録 |

### 4.7 統計（写真全体の値）

| 値 | 現行の定義 | GPU 版の方法 | 結果の扱い |
|---|---|---|---|
| 自動露出 p97 | 中央 1/2×1/2 の輝度、NumPy float32 linear（近い端点から補間） | 入力は CPU にもあるので、**CPU で求める**（`R7PreviewInputs.prepare` をそのまま呼ぶ） | 両モードとも写真open時に1回。初回準備として①から除外（#323） |
| 局所トーン p50/p92 | 原寸に拡大した平滑 log 輝度の全画素 | Compute シェーダーで `OriginalQuantiles` と同じ2段の基数ヒストグラム（上位16bit → 該当バケット内の下位16bit）を作り、厳密に選ぶ | FULL_RECOMPUTEは毎frame。NORMALはR7の既存cache条件に従う。CPU分位点との差は参考記録、GPU full/bandの判定値一致を維持 |
| 白に近い画素の割合 | Glow 後の線形輝度 > NEAR_WHITE の画素数 | Compute シェーダーで原子的に数える | 強さ・調整値が変わるたび。CPU countとの差とGPU full/bandのcount差は記録のみ。GPU同士の最終DISPLAY8・分位点・Mist factor一致は別に検査 |

### 4.8 書き出し（帯ごとの計算と「場の境界」）

原寸の中間画像を GPU に丸ごと置かない。12MPの製品接続と24MPのPhase 2検証は区別する。工程の途中に、写真全体から作る縮小場が必要になる箇所（**場の境界**）があるため、次のように区切る。

| 境界 | 必要な場 | 境界より前に計算する工程 |
|---|---|---|
| B1 | 局所トーンの格子（BOX 縮小）と p50/p92 | warmth → profile → log 輝度 |
| B2 | 肌の重みの縮小場、Glow の bloom・halation の縮小場 | B1 まで ＋ tone → foundation → vibrance/crisp/skin |
| B3 | diffusion の縮小場 | B2 まで ＋ Glow 合成 |
| B4 | 白に近い画素の数、Mist の縮小場 | B3 まで ＋ diffusion 合成 |
| B5 | Orton の縮小場 | B4 まで ＋ Mist 合成 |
| 最終 | なし | B5 まで ＋ Orton → Character → skinChromaCap → shoulder → sRGB 8bit |

- **各境界で、原寸入力を帯ごとに最初から計算し直す。** 既定256行、試験は64行と最終端数帯も扱う。原寸のGPU中間画像や中間Float32ファイルを残さず、前工程を再計算する。再計算の速度・全RAM一定を仕様だけで保証しない。
- 縮小場（BOX）は帯を順に加算しGPUへ保持する。`verticalFirst` も全画面GPUと同じ軸順・Float丸めを保つ。初期案の常時「横→縦」では縦長/一定色等で全画面GPUと異なったため、#338の順序修正を使う。帯64/256、継ぎ目・端・端数帯を含めGPU同士を比較し、不一致は失敗とする。CPU倍精度との差は別の参考値。
- 拡大（BILINEAR/BICUBIC）は画像全体の座標と縮小場から帯を計算する。帯ごとにローカル座標へ丸め直さない。
- 入力は既存LibRawの未調整RGB16原寸ラスタを行順に読み、CPUで有限RGBA Floatへ詰めてRGBA32Fの帯textureへアップロードする。初期案のR16整数texture直接入力は使っていない。欠落・追加・順序不正の行を拒否し、warmthはGPU内で一度だけ適用する。
- 最終RGBA8を帯/行ごとに読み戻し、既存Bitmapの `setPixels`、JPEG95の `Bitmap.compress`、出力再読取・原本前後照合・publicationを共用する。Bitmapを含む全RAMはtexture台帳と別。24MPの行JPEG/省メモリ化は [Phase 2](GPU_EXPORT_PHASE2.md) の別範囲で、ここでは採用しない。
- 帯ごとに取消とfence完了を確認し、未処理帯を大量にqueueへ先行投入しない。投入済みGL/native処理の即時停止や一定ms以内の停止は保証しない。
- `Original24Attempt` は最初の実保存時にengineを固定する。GPUではpreview owner終了後に専用GL ownerを開始し、原本照合・durable intent・Foreground Service・`Original24Resources` の確認を共用する。GPU_FAILEDの明示retryと取消/HOLDの境界は§4.12に従う。

### 4.9 プレビュー（表示と操作）

- **入力解像度**: Phase 0 の GPU プレビューは **長辺 1024px 以下、かつ 786,432画素（1024×768）以下**。撮影で用いる4:3・16:9は従来どおり1024×768・1024×576（縦向きは転置）とし、近正方形だけ面積上限にも収まるよう縦横比を保つ。正方形の例は886×886。
  - 寸法は入力縮小段階で `min(1, 1024/長辺, sqrt(786432/画素数))` の共通倍率と各辺floorで一度決定する。既に両上限内なら寸法を変えない。GPU用DNG入口は既存RGB16→BOX縮小へこの寸法を渡し、通常CPUプレビュー・書き出しの寸法規則は変更しない。
  - rendererとStageClearGpu.openPhotoは、この寸法決定済み入力を受け取る。どちらかの上限を超えた場合は、既存checkと同じ明示失敗にし、GPU内部で再縮小しない。
  - 理由: 長辺だけでは1024×1024も許容され、1024×769のメモリ計測から最大面積の安全性を保証できなかった。撮影の4:3/16:9を維持しつつ、ユーザー判断でGPUプレビューの面積も制限する。当時のpreview 96MiB（#323で128MiBへ事後変更）、書き出し192MiB、ルックの式、画素差の基準は不変。
  - 最大メモリの確認形状は1024×768、768×1024、1024×576、886×886。従来1024×769は上限外の履歴比較として旧診断候補でAndroid実測と照合し、現行入力の受け入れに含めない。画面サイズへの拡大は表示パスで行う。
- **表示面**: Composeの `AndroidView` + 非再利用 `SurfaceView` を使う。正のlayoutサイズ確定時に描画を1回要求し、接続済みSurfaceからのrenderer交換でも作成通知が届くようにする（#339）。EGLSurfaceは生成/破棄に追従し、detachは2秒で区切る。時間超過はtyped GPU failureで表示し、終了不明ownerを捨てない。
- **描画の流れ**: スライダーの値が変わる → `GpuRenderParams` を GlThread のキューに置き換え → 次のフレームで1回描画。
  - NORMALは強さ変更時にR7/Glowを保持し、色温度/明るさで無効化。FULL_RECOMPUTEはR7分位点・Glowの場・Mist判定・最終表示まで毎frame再計算する。写真の自動露出は両モードで初回のみ（#323）。
- **二段構えはなくす**: ドラッグ中も指を離した後も同じ正確な描画。CPUの256px粗表示actorはGPU経路では使わない。GPU自身の最新要求への合流、古い写真/世代の失効、取消・lifecycle検査は維持する。
- **比較表示**: `showBefore = true` のときは AS_SHOT 入力をそのまま sRGB 表示する（現行の比較画像の定義と同じ）。
- **原本の照合**: 写真を開くときと保存の前後で行う（#268 の境界のまま）。フレームごとには行わない（入力は GPU に常駐し、ファイルを読まないため）。
- #339/#341で既存編集画面へ接続したが、専用gpuDiagnostic Hubからの明示選択に限る。通常debug/releaseはCPU。Files復帰時はforeground・camera受付・Surfaceをeventで待ち、10秒で明示失敗とする。受付後native展開はこの10秒に含めない。

### 4.10 計測

- `GpuFrameStats`: CPU 側の投入時間、`GL_EXT_disjoint_timer_query` が使えれば GPU 時間。
- 診断版の画面で、フレーム時間の中央値・p95・最大、メモリ台帳、温度状態を記録する。写真名・写真ごとの数値は Git・Issue・PR に入れない。

### 4.11 正しさの検証

2026-11-11の[本人判断](https://github.com/reitojike/cinematic-camera/issues/276#issuecomment-6809283080)により、CPU数値差は参考記録のみ。旧閾値の超過をGate 2やGPU結合試験の停止・不合格にしない。finite/shape/type、コピー、GPU同士、資源・安全性の失敗は別であり、削除しない。

| 種類 | 内容 | 場所 |
|---|---|---|
| 合成画像セット | 決まった乱数で作る合成画像（グラデーション、白飛び、肌色、彩度の高い色、暗部、ノイズ）。Mesa CI は64×48・256×192・1600×1200。Androidは256×192と公開入力上限内の1024×768など各工程の受入寸法。単体passの旧1024×769 fixtureは履歴比較として保持 | 共通の `androidTest/.../reference/GpuReferenceImages.kt` を直接利用 |
| 工程ごとのCPU参考値 | `GpuStageTap`で読み戻し、Kotlinとの差・最大位置・旧基準比を `CPU_REFERENCE_ONLY` として記録。差だけで失敗させない | MesaとAndroidを別々に記録 |
| 最終出力のCPU参考値 | 全画素最大・平均・全RGB p99.9・変更画素数を記録。旧Orton除外判定も履歴用。超過は停止理由にしない | 同上 |
| 判定値のCPU参考値 | nearWhite count、Mist自動強度、局所p50/p92を記録。GPU readbackをCPU sortで計算した分位点もCPU参考値 | 同上 |
| 自動判定を維持 | GPU帯/full・64/256・cache/freshの一致、shader compile/link、有限値・寸法・入力保護・retry・解放 | 下記実行範囲を区別 |
| Gate 2 | 本人の実写の見た目でGPU previewと保存JPEGを判断。CPU JPEGは比較参考。写真・写真別数値・素材名・個人パスは公開しない | 本人が確認した指定APK/実施範囲 |

#### Orton の1.0境界に対する旧表示判定（履歴・現在は参考値）

以下は[#276](https://github.com/reitojike/cinematic-camera/issues/276)・[#285](https://github.com/reitojike/cinematic-camera/issues/285)で当時承認された判定と測定の履歴。旧判定器自体の集計試験を維持するが、現在のCPU/GPU比較はこれを参考記録に使い、assertPassedを呼ばない。
対象は **Kotlin見本の `StageClearOrton.mixOrton` に渡すimage（MIST）だけ** から決める。
いずれかのRGB成分について `abs(component.toDouble() - 1.0) <= 1e-5` の画素を対象とし、GPU値では選ばない。

- 比率は比較画像ごとに対象画素数 / 全画素数 ≤0.01%。整数の `対象数 * 10000L <= 全画素数` で判定し、超えたら不合格。
- 対象画素の全RGBを **最終表示8bitの最大差≤2だけ** から外す。99.9パーセンタイル≤1は対象を含む全RGBで計算する。分母や補間方法は変えない。
- 毎条件、対象数・比率・全対象の位置・Kotlin/GPU入力RGB・Kotlin/GPU表示RGBと差を記録する。対象0でも要約を残し、判定失敗前に保存する。
- Orton以外の工程判定、Mist自動強度差0.005、局所分位点差1e-4、float単体判定、式・shader・予算は変えない。float中間画像へこの例外を広げない。

理由は既存Kotlin式 `if (image[j] > 1f) max(image[j], value) else value` の1.0での値の飛び。
MIXED 256×192 / strength0.5 / warmth0 / userEV+1 の(248,102)赤では、Kotlin入力1.000000238418579に対し
Mesaは0.9999969601631165、指定Androidエミュレーターは0.9999984502792358となり、反対側の分岐へ入った。
表示は双方245対240で、旧全画素最大差は5、全RGB p99.9は0。
Kotlinだけで選んだ対象は(248,102)と(142,121)の2/49,152画素（0.0040690104%）。
対象外最大差1・全RGB p99.9=0で、この1条件は新基準を満たす。
[Androidの入力/value/分岐直後/保護後の記録](https://github.com/reitojike/cinematic-camera/issues/282#issuecomment-6761721229) を参照。
以前のCPU/GPU union対象除外後の分位点は診断値であり、この正式基準では使わない。
この1条件の再評価と全行列を区別する。接続候補2e0a2aaでは新基準のMesa200/200条件がPASSし、
対象外最大1、全RGB p99.9最大1、対象比率最大0.0081380208%を確認した。
指定Android全行列と最終候補の受け入れは別途検証し、Gate1全体・実機の合格とは扱わない。

CPU差の超過だけを式の誤りと断定しない。shader/原本保護の不具合は別に調査し、差を消すための無断の式変更・閾値拡大・baseline自動更新は行わない。

#### 2層の実行環境（#290）

GPUのないLinux CIのエミュレーター/GMDでは、現在の `#version 320 es` が要求するES 3.2を提供できない。
host GPU + `GuestAngle,Vulkan` の通常AVDでの成功を、GPUのないGMDでも実行可能という根拠にはしない。
従来のGMD CI案を次の2層に置き換え、どちらかの成功を他方の合格へ読み替えない。

- **Mesa CI**: MesaのEGLでGLES 3.2 contextを実際に作り、GLSLの計算と回帰を検証する。
  実assetを現行 `ShaderSource` / `ShaderConstants` で展開し、コピー・CI専用の書換えはしない。
  共有 `GpuReferenceImages` / `ImageDiff` / `GpuTolerances` とKotlin計算を参考観測に使い、旧閾値そのものをMesa向けに変えない。GPU帯/fullの一致とfinite等は必須に保つ。
  CPU差は工程・条件・Kotlin/GPU値・差を参考値として報告する。Mesa版、EGL driver名、renderer、実ES版、件数、失敗、skip、所要時間をCIログへ出す。
- **Android opt-in**: host GPU + `GuestAngle,Vulkan` の通常AVDで、対象Android instrumentation試験を**GPU PRごと**に実行する。
  最終候補head、実行件数・成功/失敗/skip、実contextのES版、環境をPRに記録する。記録がないPRはマージしない。
  AndroidのEGL/GLES binding、resource寿命、pass graphとの結合はこの層で検証し、Mesa用host harnessの成功では代替しない。
- 4000×3000以上は引き続きopt-in。[Gate 2本人承認](https://github.com/reitojike/cinematic-camera/issues/291#issuecomment-6813411664)は指定APKの実写2場面のGPU preview/保存JPEGと参考CPU JPEG1枚に限る。#289/#290とは並行で実施済み。性能・メモリ・故障/取消・他の版の実機合格を含めない。

Mesaは既存の独立必須ジョブで `-PcinematicGpuMesa=true --tests 'dev.cinematic.p0.gpu.mesa.*'` を付けた
`:app:testDebugUnitTest` を実行する。必要suite/試験種別の欠落、0件、failure/error/skipは失敗。
assetは全件compile/linkし、未知拡張子や未検査assetも拒否する。追加Mesaクラスもwildcardの対象になる。

| 実行場所 | 継続対象 | 実行していない範囲 |
| --- | --- | --- |
| Mesa `MesaShaderAssetsTest` | 全製品asset compile/link、include読取網羅 | compileだけでは数値pass graphの実行を証明しない |
| Mesa `MesaImageAgreementTest` | 64×48・256×192・1600×1200×共通10fixtureのRGBA32F copy完全一致と実color assetのCPU参考差 | R7/全Lookはここでは描画しない |
| Mesa `MesaExportBandsTest` | 実reduce/accumulate/upsample shader、64/256/端数帯、縦長/一定色/縞/impulse、GPU full/band一致とCPU参考差 | B1〜最終保存の全pass graph、DNG/JPEG/publicationは実行しない |
| Mesa `MesaReferencePolicyTest` | 大きいCPU差が参考値のまま、非finite/shape不正は拒否 | 実写品質の判定ではない |
| 通常/診断CI | JVM安全・設定・集計・CPU golden、lint、APKとinstrumentationのcompile、依存隔離 | Android instrumentationはCIでは実行しない |
| Android opt-in | 対象PRに応じたR7、Glow/Mist、finish、全工程、帯/full、preview/export owner、安全試験 | 継続Mesa CIや未実施の実機測定へ読み替えない |

旧CPU数値assertの棚卸し対象は色/resample、R7/分位点、Glow/Mist/nearWhite、finish/Orton、全工程、
display/previewの中間色、合成DNG、旧GPU合成診断。GPUコピー・画面配置、cache/fresh、帯/full、
係数のuniform転送、CPU入力準備値の受渡し、純粋な集計器、CPU goldenと保存安全試験は維持する。
この層の区分と個々の最終headの実行件数はPRへ記録する。過去にhostで成功した全Look行列を継続Mesaへ統合済みとは書かない。

#### 後段B: GPU既定化後のルック正本・基準画像

- #289と前段Aの受入後、別途本人承認されたGPU既定化commitを確認してから開始する。今回GLSL正本への切替、golden生成/採用は行わない。
- 既存の決定論的合成fixtureを使い、入力/seed/寸法・調整値・GLSLと注入定数の版・色空間/8bit化・Mesa/driver/実ES・基準hash・比較方法を管理する。実写を公開goldenに入れない。
- 同一環境のGPU回帰と異なるdriverの比較を区別し、CPU閾値を無断転用しない。意図した見た目の変更は本人確認後にbaseline更新し、失敗を消す自動再生成は禁止。製品Kotlinの数式・CPU golden削除は含めない。

JVM bindingは **LWJGL 3.4.3** の `org.lwjgl:lwjgl`、`lwjgl-egl`、`lwjgl-opengles` と
`lwjgl` / `lwjgl-opengles` の `natives-linux`。明示フラグ時の `testDebugImplementation` / `testDebugRuntimeOnly` だけに隔離し、APK・製品依存・通常のJVM試験・他variantには加えない。
ライセンスは [BSD-3-Clause](https://github.com/LWJGL/lwjgl3/blob/3.4.3/LICENSE.md)。この5座標の3.4.3だけを利用者が承認した。
製品依存、他の構成・プラットフォームのnativeへの追加には承認を広げない。
CIは通常の `debugRuntimeClasspath` / `debugUnitTestRuntimeClasspath` の依存レポートと、
通常debug APKのnative一覧・DEX定義package一覧にLWJGLがないことを照合し、証拠をartifactへ保存する。
追加や版変更時は依存名と版をPRへ記載し、**利用者の承認前はマージしない**。
EGL/GLES bindingは [LWJGL公式](https://www.lwjgl.org/guide) と
[EGL API](https://javadoc.lwjgl.org/org/lwjgl/egl/EGL15.html) を利用する。

### 4.12 失敗時の扱い

- GPU能力不足・context喪失・予算超過等は見える失敗にし、**CPUへ自動fallbackしない**。previewは選択写真の画像領域に理由と明示retryを示し、旧owner終了確認と原本再確認後だけ新sessionを開く。
- 保存は固定理由/当該ownerの回数を表示する。JPEG入口前のGPU producer failureでGPU・原本・作業領域・出力の全cleanupが確認できた時だけ、同じsource/recipe/generation/engineで明示retryする。失敗中の別編集世代・別engineへの迂回はしない。
- #346の取消契約もJPEG入口前・全cleanup完了が確認できた同プロセスの同世代だけ明示retryを許す。解放不明・JPEG入口後・restart・intent不一致はHOLD/既存publication再照合を維持する。durable intentを消して回避しない。
- 原本の強い照合、Foreground Service、既存publicationは共用する。画面の取消/失敗表示とowner回収を分け、実施していない故障注入をGate 2承認へ含めない。

### 4.13 切り替えフラグ

- `DevelopEngine { CPU, GPU }` をpreviewとexportで共用し、保存は最初の実保存時にengineを固定する。
- 専用gpuDiagnosticのHubだけでGPUを選べる。通常debug/releaseはCPUで、通常設定画面にselectorを追加しない。
- Gate 2本人承認済みでも、#289と#290前段Aの受入後に別途GPU既定化PRを作り、マージ前に本人確認する。後段BのGPU基準画像はその後に進める。

---

## 5. GLSL の契約（#277 PR1）

- 先頭は `#version 320 es`、`precision highp float; precision highp int; precision highp sampler2D; precision highp isampler2D;`
- GLSL ES の予約語（`input`、`output`、`sample`、`filter`、`common`、`half`、`fixed` など）を変数名・引数名に使わない。
  Kotlin の変数名を写すときも予約語を避ける。例えば `half` は `halfWidth` とする。
  フラグメントシェーダーの `isampler2D` には既定の精度がないため、サンプラー型の精度も上記のように明示する。
- uniform は `u_`、サンプラーは `s_`、フラグメントの出力は `o_color`、共通関数は `cs_` で始める
- `#include "common/color.glsl"` の形で取り込む（`ShaderLibrary` が展開する）
- 定数は `#define SC_<接頭辞>_<定数名>` で注入される。接頭辞は `StageClearV1`→`STAGECLEAR`、`StageClearGlowV1`→`GLOW`、`StageClearMistV1`→`MIST`、`StageClearOrtonV1`→`ORTON`、`StageClearCharacterV1`→`CHARACTER`、`R7BaseV1`→`R7`（例: `SC_GLOW_THRESHOLD`、`SC_R7_CONTRAST`）。
- 配列は要素ごとに展開する: `FloatArray` は `SC_STAGECLEAR_RGB_TO_LMS_0`〜`_8`、`Pair` の配列は `SC_GLOW_BLOOM_KERNELS_0_RADIUS`・`SC_GLOW_BLOOM_KERNELS_0_WEIGHT` の形。要素数も `SC_GLOW_BLOOM_KERNELS_COUNT` として出す。
- **シェーダーに V1 の定数を直書きしない**（式の中の 0.5・1.0・3.0・.025 など、Kotlin の式に直接書かれている数値は同じように書いてよい）

共通 include ファイルには `#version` を置かない。各エントリーポイントが上記のヘッダーを持ち、
`ShaderLibrary` がその `#version` の直後へ定数を注入し、include を展開する。
PR1 の共通関数は契約だけの仮実装であり、値を返す全関数に `return 0.0`・`return vec3(0.0)`・
`return vec4(0.0)` を置く。未定義の戻り値やドライバーごとのコンパイル差を避けるためであり、
画像処理としての実行は #279 が実際の計算式に置き換えて試験した後に行う。

### 5.1 共通関数

```glsl
float cs_signedCbrt(float x);
vec3 cs_linearToLab(vec3 rgb);
vec3 cs_labToLinear(vec3 lab);
float cs_linearToUnit(float v);
float cs_unitToLinear(float v);
float cs_encode(float linear);
float cs_decode(float display);
float cs_hueDeg(vec3 lab);
float cs_band(float hue, float centre, float halfWidth);
float cs_skinWeight(float l, float c, float hueDeg);

vec4 cs_resampleAxis(sampler2D s_input, sampler2D s_weights,
    isampler2D s_first, ivec2 outputPixel, bool horizontal);
```

リサンプルは出力1画素の1軸ぶんを計算する。重みテクスチャは R32F（幅=最大タップ数、
高さ=出力軸サイズ、不足列は0）、先頭テクスチャは R32I（幅=1、高さ=出力軸サイズ）。
`outputPixel` は出力の整数座標、`horizontal` は横軸なら true。タップ数は重みテクスチャの幅から得る。
平面は戻り値の R、RGB は RGB 成分を使う。計算の実装と試験は #279 が担当する。

## 6. 並行実装の境界

- `StageClearGpu.kt` は §4.2 の API のみ。PR1 では実装も製品の呼び出しも追加しない。
- `GpuPreviewTarget` は表示先の不透明な型。EGL の実体・所有権の実装は #277 PR2 と #283 で扱う。
- `GpuStageTap.Point` は次の順序を固定する:
  `INPUT, WARMTH, R7_PROFILE, R7_TONE, R7_FOUNDATION, R7_FINAL, SKIN_WEIGHT, GLOW, MIST, ORTON, CHARACTER, SKIN_CAP, SHOULDER, DISPLAY8`。
- `GpuAnalysis.localToneQuantiles(input: GpuTexture, width: Int, height: Int): DoubleArray` は #280 が実装する。
  戻り値は2要素で、順番は p50、p92。
- `GpuAnalysis.nearWhiteCount(input: GpuTexture, width: Int, height: Int): Long` は #281 が実装する。
  両関数は PR1 では未実装として失敗させ、もっともらしい仮の数値を返さない。各担当は自分の関数の中身だけを変更する。
- `assets/gpu/common/*` と `gpu/resample/*` は #279、`r7/*` は #280、`look/*` は #281・#282 の担当。
  共通関数が足りない場合は各自のフォルダに一時的に置き、#284 の統合で common へ移す。
- `look/StageClearLookGpu.kt` に #281 が指定する受け渡し型だけを先に定義する:
  `MistGpuResult(val image: GpuTexture /* RGBA32F */, val lightAddingStrength: Double, val skinWeight: GpuTexture /* R32F */)`。
  #281・#282 はこの型を共用し、自分の別ファイルに処理を実装する。型の重複定義や互いのマージ待ちは不要。
- `GpuUnavailableException`・`GpuContextLostException`・`GpuMemoryBudgetException` の定義は #288 の
  `gpu/GpuFailures.kt` に一本化する。#277 PR2 はその型を利用し、同名の例外を別ファイルに重複定義しない。
- `raw/*` と `raw/original/*` のリファレンス計算、既存の gpuDiagnostic、製品の呼び出し元は変更しない。
- GlThread・EGL・テクスチャ・シェーダー読み込み・パス・読み戻しの実装は #277 PR2。
  PR1 のパッケージだけのファイルは担当者の作業場所を確保するための空の枠である。

### PR2 内部リソースの利用（#277）

固定の公開署名は変更しない。解析関数は `input.context` / `input.memoryLedger` から所有元を取得し、
`EglContexts(glThread, assets)` に明示注入した assets を使う `context.shaders()` / `context.passes()` を借用する。
これらは context ごとに再利用され、context 破棄前に解放される。借用側では個別に close しない。
`ShaderLibrary.fragment/compute` のキャッシュ済み program も library 所有であり、呼出側では close しない。
能力検査だけの `EglContexts(glThread)` は assets 不要。global / ThreadLocal / 別 context は導入しない。

Orton 準備の encoded / bright は `FragmentPass(output = encoded, additionalOutputs = listOf(bright))` で
同じ FBO の location 0 / 1 に出す。全出力の寸法・context を一致させ、入力との feedback を禁止する。
テクスチャの所有権は呼出側に残り、組合せ FBO だけをパスの finally で破棄する。
