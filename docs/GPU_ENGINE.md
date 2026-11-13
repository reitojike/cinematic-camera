# GPU 描画エンジンの契約

[親 Issue #276](https://github.com/reitojike/cinematic-camera/issues/276) §4 と
[契約 Issue #277](https://github.com/reitojike/cinematic-camera/issues/277) PR1 を転記した設計の正本。
現行の API・GLSL・実装設計を記録する。Draft PR中の変更はマージまで提案であり、mainの契約を置き換えない。
進捗・検証結果・採否は Issue / PR に記録する。通常debug/releaseの新規プレビュー・保存は #354 でGPU既定化済み。診断・旧保存世代のCPU依存は残る。
#291は記録された診断APKの実写確認と本人承認を完了した。後続版・未確認条件の合格へ広げない。
CPU数値差は参考値。#290のGPU基準画像回帰は現行GPU出力を対象にAndroidで先行し、既定切替や様子見期間の終了を待たない（§4.11）。
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
- **コンテキスト**: #341の製品GPU接続はpreviewとexportを独立owner/thread/contextにする。保存前に保存対象Aのpreview owner終了を確認し、保存中は同じAのpreviewを停止する。保存後のAのpreview再開は明示操作。別写真Bは独立した入力・preview ownerで準備・表示できる。初期案の共有context・同じAの同時描画は採用していない。同じAの原本展開と表示の所有期間を重ねず、解放不明ならその次ownerを開始しないためである。
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
- `Original24Attempt` は最初の実保存時にengineを固定する。GPUでは保存対象Aのpreview owner終了後に専用GL ownerを開始し、原本照合・durable intent・Foreground Service・`Original24Resources` の確認を共用する。GPU_FAILEDの明示retryと取消/HOLDの境界は§4.12に従う。

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

#### GPU基準画像回帰: Android先行とD0のMesa移行

- 現行GPU出力を比較対象とし、Android opt-inによる候補生成・比較をGPU既定切替前から様子見期間と並行して進める。#290前段AのCPU差参考記録化と、基準画像の候補生成・本人採用・継続比較は別々に検証・記録する。前段Aだけで基準画像を作成・採用済みとはしない。
- 既存の決定論的合成fixtureを使い、入力/seed/寸法・調整値・対象commit・GLSLと注入定数の版・色空間/8bit化・基準hash・比較方法を管理する。実写・個人情報を公開goldenに入れない。
- Android先行時はホストGPU/driver版、AVD名・system image/API/ABI/版、emulator版、描画設定、実EGL/GLES vendor/renderer/ES版も基準画像と一緒に記録する。driverやAVD等の版が変わって差が出た場合は差の場所と大きさを示し、本人の目視確認後に基準を作り直す。環境差を無断の閾値緩和や自動baseline更新で消さない。
- 基準候補は本人の目視確認後に採用する。同一環境のGPU回帰と異なるdriverの比較を区別し、CPU閾値を無断転用しない。意図した見た目変更時も本人確認後にbaseline更新し、失敗を消す自動再生成は禁止する。
- この全工程の基準画像比較は現時点ではCI自動実行ではない。少なくともGPUやルックに触れるPRのマージ前、およびCPU経路の削除・リファクタリングの各PR前に手動実行する。対象head、基準hashと環境、条件数・成功/失敗/skip、差分と本人確認の要否をPRへ記録し、未実行をCI成功で代替しない。
- v0.1.0前のD0ではMesaから実全工程を呼べる境界を設計する。CPU/診断削除・試験移動後のD1〜Dnを経て、基準画像比較をMesa CIへ移す。現在のMesaは上表のpass範囲であり、全工程の基準画像比較を移行済みとはしない。MesaとAndroidのdriver差は別に評価し、Android基準へのbit完全一致を無断で条件にしない。
- 基準採用だけでは製品変更や削除を許可したことにはしない。GPU既定化は#354で実施済み。今後のGLSL正本化・CPU整理は以下のロードマップv2の条件に従い、見た目・原本/保存データに影響する変更は個別承認する。

JVM bindingは **LWJGL 3.4.3** の `org.lwjgl:lwjgl`、`lwjgl-egl`、`lwjgl-opengles` と
`lwjgl` / `lwjgl-opengles` の `natives-linux`。明示フラグ時の `testDebugImplementation` / `testDebugRuntimeOnly` だけに隔離し、APK・製品依存・通常のJVM試験・他variantには加えない。
ライセンスは [BSD-3-Clause](https://github.com/LWJGL/lwjgl3/blob/3.4.3/LICENSE.md)。この5座標の3.4.3だけを利用者が承認した。
製品依存、他の構成・プラットフォームのnativeへの追加には承認を広げない。
CIは通常の `debugRuntimeClasspath` / `debugUnitTestRuntimeClasspath` の依存レポートと、
通常debug APKのnative一覧・DEX定義package一覧にLWJGLがないことを照合し、証拠をartifactへ保存する。
追加や版変更時は依存名と版をPRへ記載し、**利用者の承認前はマージしない**。
EGL/GLES bindingは [LWJGL公式](https://www.lwjgl.org/guide) と
[EGL API](https://javadoc.lwjgl.org/org/lwjgl/egl/EGL15.html) を利用する。

#### D0 と D1〜Dn、CPU 整理の順序（ロードマップ v2）

管理先は既存 #290 / #276。#349 の Android 手動基準登録は main に反映済み。#355 の既知共通黒化は PR #361 で修正し、本人確認済みの修正後90条件基準をmainへ反映済み。基準の再採用・閾値変更は今回行わない。CPUへ戻す運用は想定しないが、削除は次の条件と手順を満たしてから行う。

#357 / PR #364 の同プロセスの確認済み取消後の新世代受付は反映済み。次の #365（確認済取消の永続証明）は保存まわりのリファクタリングより先に実装するが、実装範囲を本人へ提示してから着手する。本D0はその依存と互換試験を整理するだけで、証明schema・保存状態・復旧契約を実装しない。公開状態不明・cleanup未確認のHOLDを維持する。24MPと同じ保存ファイルに触れる場合は担当間で調整する。

1. **D0 は今から並行して進める設計文書**: GPU前提で切替機構の撤去、preview/export shader構成の共通化、責任分担、失敗型、命名、試験の配置、Mesaから実全工程を呼べるbinding境界、shader定数の正本を決める。D0完了をMesa実装完了と呼ばない。
2. #290後段BでGLSLを正本とし、GPU基準画像との回帰をCPU削除・リファクタリングの前提にする。CPU削除前にshader定数の出どころも確定する。Mesa全工程CIの完成はCPU削除の前提に加えない。旧CPU数値閾値を合否へ戻さない。
3. CPU・診断経路の削除は2026-11-15ごろに本人が判断する。それまではD0と削除準備までとし、日付の到来を許可や合格とみなさない。本人の普段使い結果（保存、保存中の次写真表示、取消→やり直し、#352の再発有無）と、データ損失・保存不能継続など致命的不具合の有無を提示して判断を受ける。小さい問題はIssueで並行追跡する。
4. 安全に削除できる旧spec/計測/RAW診断/rawprobeは依存棚卸し後に小PRで整理する。共有部分を先に分離する。gpuDiagnosticはGPU試験を通常の試験側へ移した後に削除する。
5. CPU/診断経路の削除と試験移動後に、D0範囲の挙動不変なリファクタリングをD1〜Dnの小PRで進める。24MPと同じファイルは24MPを先にする。
6. **Mesa全工程の基準画像比較CI移行はD1〜Dnの後**。それまではGPU/ルックPRのマージ前、CPU削除・リファクタリングの各PR前にAndroid手動回帰を必須とする。AVDは1枠で24MP→v0.1.0クリティカルパス→その他の順。

| 領域 | 再利用・維持する契約 |
| --- | --- |
| shader / 共通pass | 現Mesaの全asset compile/link、color/copy、部分resample/bandを再利用。実graphを呼び、別shaderやCI専用rendererで代用しない |
| 基準比較 | 同一環境の回帰とdriver差の評価を分ける。基準採用・更新は本人確認、自動更新やCPU閾値流用は禁止 |
| 安全契約 | Android owner/取消/HOLDとMesa finite/shape/帯fullは別層。missing/zero/failure/skip拒否を維持 |

CPU整理の境界:
- **削除候補**: ManualTrialPhotoRollRendererのCPU draft/exact、StageClearTrialPipeline/Renderer、R7Base/StageClear画素実行、OriginalStageClear24/JpegGenerationAttemptのCPU経路。診断・試験・既存保存世代の依存を先に照合する。
- **GPU共有で維持**: LibRaw、OriginalRaster/Fields、Original24PreviewSessionのAS_SHOT、R7PreviewInputs、PhotoAdjustments/画像型、StageClearBlur係数。ShaderConstants参照V1定数は新しい正本をD0で決めてから移す。raw/sessionを一括削除しない。
- **保存安全・互換**: 原本、世代/engine固定、journal/publication、FGS、owner、retry/HOLDを維持。永続化済みCPU保存世代をGPU世代と再解釈せず、安全な復旧・完了互換を確認する。原本/保存データに影響しうる変更は個別承認する。
- **参照と履歴**: KotlinReference、CPU golden、旧診断履歴は代替試験と保存先・削除範囲を確認する。GLSL正本化だけを根拠に無差別削除しない。

初回表示 #233/#241 と顔検出 #79 は調査・設計を先行し、実装は24MPと整理が落ち着いてから。初回表示は#260と競合するため24MP後、顔のGPU連携はリファクタリング後。性能は計測計画・分析を並行可とするが改善実装、撮影look #80、追加look #83 は後段とする。顔検出ライブラリ採用、実機計測、見た目変更は別途本人承認する。

#### D0: 現行コードに対する責務・依存の棚卸し

初回照合基点は main `579500f`（#356 / #359 反映後）。main `3a92047` への取込み時に、#357（PR #364）・#360・#367・#369の反映を再照合した。以下は削除・分離の設計案であり、コード変更、CPU廃止、Mesa全工程CI移行の完了を表さない。現行契約は [develop](../specs/develop/spec.md)・[storage](../specs/storage/spec.md)・[diagnostics](../specs/diagnostics/spec.md) を優先する。削除準備は main `ad911a6` で再照合し、#361（#355と修正後基準）および #370〜#374（取込み・24MP合成試験）の反映を確認した。後続PRはその時点のmainへ再照合する。#357の確認済み取消後の新世代受付と#360の診断追加は反映済みだが、旧CPU更新互換・24MP性能の動的合格を意味しない。

表のコードパスは `app/src/main/java/dev/cinematic/p0/` からの相対。保持は永久固定という意味ではなく、挙動・互換性を置換する根拠が揃うまで削除不可という意味である。

| 責務・コード根拠 | 保持する境界 | 削除・分離候補と先行条件 |
| --- | --- | --- |
| `gpu/DevelopEngine.kt`: `defaultDevelopEngine` / `DevelopEngineSetting.beginExport` | 通常debug/releaseはGPU、他variantはCPU既定。選択UIはgpuDiagnostic限定。保存開始時の固定値と能力不足の見える失敗 | 診断選択UI・設定読書き・新規CPU入口は候補。enumやCPU既定引数の一括除去は不可。診断移動と旧保存互換を先に監査 |
| `photoroll/ManualTrialPhotoRollRenderer.kt`: GPU準備とCPU draft/exactの分岐。`StageClearTrialPipeline.kt` / `StageClearTrialRenderer.kt` | 同じ写真・調整値、比較表示、取消、原本再照合、GPU preview ownerの終了確認 | CPU draft/exactと試験描画本体は候補。`TrialStrengthEditor` 等の編集操作やGPU入口を名前の「Trial」だけで削除しない |
| `gpu/StageClearGpu.kt` 内の `ConnectedStageClearGpuPipeline`、`gpu/preview/` | AS_SHOT入力・写真解析・NORMAL cacheとFULL_RECOMPUTE・GL thread/context単位の所有。旧A完了を新Bへ反映しない | UI/lifecycleと画素graphの境界を分離候補とする。公開API §4.2 の変更は親Issue合意が必要。24MPの入力/owner変更後に着手 |
| `jpegrender/original/Original24Attempt.kt` / `Original24GpuOutput.kt` | snapshot、native decode、寸法/geometry確認、解析、Bitmap/JPEG、全close確認をCPU/GPUで共用。JPEG入口の不可逆証跡 | `OriginalStageClear24` 呼出しとCPU pack分岐だけが画素削除候補。attempt全体やJPEG入口フラグ・cleanupを削除しない。#260/#101と保存互換監査が先 |
| `raw/original/OriginalStageClear24.kt`、`raw/R7Base*` / `StageClear*`、`jpegrender/JpegGenerationAttempt.kt` | GPUから参照される型・定数・解析・係数と、CPU診断/参照試験を区別 | CPU画素実行・旧private producerは候補。参照元を製品・診断・test別に0件確認してから限定削除。#355の画素修正と同時編集しない |
| `raw/original/OriginalRaster.kt` / `OriginalFields.kt` / `NativeOriginalRaster.kt`、`jpegrender/original/Original24PreviewSession.kt` | LibRaw入力、RGB16/行読み、AS_SHOT縮小、再読取と解放。GPUも利用する共有基盤 | raw/session一括削除は不可。CPUルックcacheとAS_SHOT入口の分離だけを候補とし、#101/#260の変更を先に反映 |
| `raw/R7PreviewInputs.kt` / `PhotoAdjustments.kt` / `StageClearBlur.kt` | `StageClearGpu.openPhoto` と `Original24Attempt` が測光準備を利用。GPU resample/exportもblurのfilter/係数を利用 | CPUという配置名は削除根拠にしない。画像型、入力解析、調整値、係数を画素実行から分ける。数値・丸め・軸順は不変 |
| `rawcapture/AndroidManualOriginalTrialSaves.kt` / `ManualOriginalTrialRequests.kt` / `ManualOriginalTrialSaveOwner.kt`、`photoroll/ManualTrialJpegSaveOwner.kt` | source/recipe/generation、単一queue、原本照合、journal/publication、FGS、retry/HOLDの権威 | 描画切替整理から独立。#357が取消後新recipeの条件を担当。D0は同ファイル・codec・世代終端条件を変更しない |

**定数の正本案**: 現在 `gpu/ShaderConstants.kt` は `raw` の `StageClearV1`、Glow/Mist/Orton/CharacterのV1、`R7BaseV1` を参照し、`ShaderLibrary.kt` 内の `ShaderSource` がdefineとincludeを展開する。GLSLの画素式を正本にすることと、このKotlin定数依存をなくすことは別である。後段Bでは、既存値をそのまま保持するGPU用の単一の定数定義へ移し、ShaderConstantsを唯一の展開口にする案を採る。CPU参照が残る間は同じ定義を参照させ、値のコピーを増やさない。名前・値・Float/Double種別・配列順・生成define全文の同一性とAndroid回帰を確認してから旧定義を削除する。数式へ定数を直書きする変更、#355の式・cache版変更、recipe版変更をこの移動に混ぜない。実際のファイル名と移動差分は後段BのPRで確定する。

#### D0: 保存世代と旧CPU互換の安全境界

`AndroidManualOriginalTrialSaves.Backend.selectedEngine` は最初の実保存で取得する**プロセス内保持値**。`ManualOriginalTrialRequestCodec` v2は原本・世代・強度・調整値・recipeを保存するがengine欄を持たない。このため「永続CPU世代をengine欄で選別してGPUへ移行できる」とは設計しない。旧requestから不明なengineや完了を推定しない。

| 観測状態 | 保持する扱い・削除前の検証 |
| --- | --- |
| 同プロセスのCPU ownerが実保存開始済み | 固定engineをGPUへ変更しない。旧owner・workerが残る状態でCPU producerを差し替えない。更新/再起動と同プロセス再試行を別ケースにする |
| JPEG入口前の確認済み取消/producer failure | 同一requestの明示retry許可はプロセス内だけに保持。#357 / PR #364で確認済み取消に限り新recipe受付を追加済み。旧ownerの取消許可・全cleanup・intent一致・publication不存在を再確認し、新request確定後に旧世代を失効させる。旧intentは消さない。producer failureには新recipe許可を広げず、取消表示だけで世代を捨てない |
| 再起動時にintentだけが残る、解放/JPEG入口/公開結果が不明 | intentだけのrestartはHOLD。既存公開記録がUNKNOWNならUNCONFIRMED/PUBLICATION_UNKNOWNとして同一publicationの再照合を維持する。intent削除、GPU再描画、旧世代の自動成功/自動retryへ変換しない。engineを復元できないことを正常GPU新規保存の証拠にしない |
| 完成JPEG/proof・publicationがある | 既存成果物と同じ公開記録を再照合。CPU成果物をGPU成果物へ再生成せず、重複公開しない。原本照合/読取権限失効・改変時の停止を維持 |
| 未開始request・別recipe受付 | request保存と実producer開始を分けて確認。generation fenceと原本同一性を維持する。#357の確認済み取消からの新世代受付を含め、CPU削除PRで更新互換ケースを再列挙する |

CPU削除のPRには、旧codec bytesの読取、同recipe再利用、異なるrecipeのfence、intentありrestart、既存JPEG再照合、publication不明、cleanup失敗のfixture対応表を付ける。`ManualOriginalTrialRequestsTest` / `ManualOriginalTrialSaveOwnerTest`、Androidの原本・GPU保存境界試験を維持する。現在の静的照合を更新互換の動的検証済みとはしない。旧データの移行・消去や復旧契約の変更は個別承認が必要。

#### D0: 診断・回帰・Mesa接続の分離案

`app/build.gradle.kts` は `gpuDiagnostic` へ `src/rawDiagnostic/java` を追加する。従ってRAW診断削除はGPU診断の共有Hub/入力/結果表示に波及する。`original24Probe`、`rawDiagnostic`、`previewDiagnostic`、`gpuDiagnostic` と `rawprobe/` は別対象として扱い、variant名から不要と判断しない。`src/gpuDiagnosticTest` のDNG入力・Files選択・lifecycle試験、`src/androidTestRawDiagnostic` 等の故障/原本検査、PR #360の並行メモリ観測の移動先を決めてからhost画面・manifest・Gradle配線を除去する。

| 層 | 現在の根拠と維持対象 | 後続の境界 |
| --- | --- | --- |
| Android全工程回帰 | `app/src/androidTest/java/dev/cinematic/p0/gpu/golden/GpuGoldenAndroidTest.kt` と採用90条件。実pipeline、環境/manifest/hashと本人目視更新 | #355の差と基準採否を先に照合。CPU削除・リファクタ各PR前の手動回帰を継続。基準の変更/閾値緩和はD0で行わない |
| Android安全性 | preview owner、保存取消/失敗/解放、原本・世代・公開、FGSとlifecycle。gpuDiagnostic専用テストも対象 | 通常試験側へ移す際はtest host・manifest・opt-in引数・実行件数も移す。クラス移動だけ、skip、コンパイル成功を実行成功にしない |
| 既存Mesa CI | `app/src/testGpuMesa/.../mesa/` の `MesaShaderAssetsTest` / `MesaImageAgreementTest` / `MesaExportBandsTest` / `MesaReferencePolicyTest`、`tools/gpu-diagnostic/check_mesa_reports.py` | 実asset全件compile/link、color/copy、部分resample/band、finite/shape・必須suite/件数/skip拒否を維持。現在は実全工程の継続CIではない |
| 参照・fixture | KotlinReference、CPU golden、合成入力、純粋なcount集計・copy・cache/freshの検査 | CPU差の参考記録とGPU同士/安全assertを分ける。代替対応表がないCPU fixture/試験は保持。CPU renderer削除とgolden廃棄を同じ理由にしない |
| 診断/計測 | `gpu/diag/GpuStageTap.kt`、FULL_RECOMPUTE、台帳・工程計測 | テストから使うtapと所有/解放観測を保持。性能のwall/texture/PSSを別々に扱い、#260のguard/予算は変更しない |

**Mesaへ渡す境界案**: 現在、`EglContexts`・`GpuTexture`・`PassGraph`・`GpuFence`・`ShaderLibrary`・graph/analysis/export各所は `android.opengl.GLES32` 等へ直接依存し、Mesa側はLWJGLの `MesaContext` / `MesaShaders` / `MesaPixels` を使用する。共通assetだけでは全工程共通実行にならない。D1以降でGL呼出し・context能力・資源生成/破棄・同期・readbackを小さいbinding境界に切り、Android実装とtest-only Mesa実装を差し込む案とする。実graphの工程順・uniform・入力解析・cache・帯halo・texture台帳を共通側に残す。UI Surface/Choreographer、Android FGS/publication、Bitmap/JPEG encodeはAndroid結合側に残し、Mesaではそれらの合格を主張しない。

preview/export共通化は、同じ式・定数・pass入出力の定義を共有する範囲に限定する。全画面previewと多走査/帯exportのスケジューラ・halo・readback・owner寿命を無理に統一しない。`GpuFailures.kt` を低水準失敗型の正本とし、固定理由への変換とUI集計、producerのJPEG前後証拠、保存HOLD判定を分ける。汎用catchで未解放を成功に変えたりCPUへ回避したりしない。

既存LWJGL 3.4.3のtest-only構成と依存非混入検査を再利用し、CI専用renderer/数式コピーは作らない。binding抽出の各段階はAndroidで挙動不変を確認し、D1〜Dnの後で同じ全工程をMesaから実行する。Mesa基準はAndroid基準の無断転用をせず、driver差・条件数・環境と本人確認を別に記録する。既存 `.github/workflows/android.yml` の必須集約（product / gpu-diagnostic / raw-diagnostic / rawprobe / jni-isolation / mesa）は、対応試験の移動をレビューするまで削らない。

#### D0: #231 の共通旧資料と正本の対応

下表はD0と重なる資料の移行境界であり、旧specの削除許可や全資料の監査完了ではない。番号は `specs/` の既存ディレクトリ接頭辞。各配下のspecだけでなくplan/tasks・補足・被リンクを削除PRで確認する。現行契約はLiving Spec、恒久設計は本書等、再実行手順は対応docs、過去の採否/実測/進捗は既存Issue/PRとGit履歴へ残す。

| 旧資料 | 現行正本・残す根拠 | 編集担当と先行条件 |
| --- | --- | --- |
| 014-stage-clear-v1、015-stage-clear-android（preview-inputs含む）、020-stage-clear-trial、034-trial-session-reuse、035-look-skin-sharing、036-photo-adjustment-pipeline、052-orton-zero-keep、056-drag-preview-decisions、058-preview-row-workers | develop/spec、本書。入力域・測光・cache・共有係数・診断限定と採否履歴を保持 | #290の画素/共有依存担当。#355採用内容を先取りせず、CPU式と共通解析を分離後に重複削除を判定 |
| 016-raw-capture-lifecycle、024-jpeg-generation、037-original-stage-clear-24mp、038-export-preview-lifecycle、043-foreground-export、047-durable-edit-values | storage/spec、develop/spec、[GPU保存接続](GPU_EXPORT_SAVE_INTEGRATION.md)。原本/品質/保存世代/再照合/FGSの未集約詳細は保持 | #290が依存表、#357が取消/新recipe、#101/#260が原寸入力/メモリを担当。共有ファイルの移行編集は各変更後に調整。旧「24MP」の名称を現製品対応済みの証拠にしない |
| 019-dng-trial-display、026-full-resolution-probe、027-baseline-jpeg-small、028-baseline-jpeg-large、040-diagnostics-design、051-gpu-band-probe | diagnostics/spec、[RAW診断](RAW_DIAGNOSTIC_BUILD.md)、[RAW probe](RAW_PROBE.md)、[基準JPEG probe](BASELINE_JPEG_PROBE.md)、[GPU診断](GPU_DIAGNOSTIC.md)。隔離・合成・非公開/製品未採用の境界を保持 | #290の診断移動担当。LibRaw/JNI・rawprobe・GPU診断host依存を照合後に整理。#360の診断fixture/計測を移す前に削除しない |
| docsのCPU並列候補（ORTON_PARALLEL_VALIDATION、CHARACTER_PARALLEL_CANDIDATE、REMAINING_ROW_PARALLEL_CANDIDATE）、PREVIEW_DIAGNOSTICS、GPU_PIPELINE_DIAGNOSTICS、GPU_GATE1_PART2 | 本書/diagnostics/specと各Issueの採否。過去の速度・台帳・失敗は当時の条件付き履歴として保持 | #290の診断/回帰担当。現行再実行入口と共有観測の有無を確認し、履歴と現行手順を区別してから整理 |
| PREVIEW_MEMORY_PROBE、PREVIEW_ORIGINAL_CHECKS、GPU_EXPORT_PHASE2 | 原本照合・メモリ観測・ロードマップの既存正本を維持 | #260/#101および並行計画担当。本D0では編集しない。最新結果を後続削除/分離PRへ再照合 |

028-face-mask-core等の顔機能、撮影/露出/UIを含む非D0資料は #231 の管理に残す。#358/#359の移行をD0でやり直さない。Living Specに残る「通常CPU」「#353マージ前提」等の旧記述は、#354後の契約と不整合になっている箇所があるため後続の担当PRで訂正する。旧資料の当時の失敗や未確認を成功へ書き換える理由にはしない。

#### D0: 削除判断前に揃える対象一覧と移行順

以下は #362 の棚卸しを具体化した**準備案**。実装・移動・削除は未実施で、2026-11-15ごろの本人判断前にCPU/診断経路を削除しない。対象外ファイルを名前の類似だけで追加しない。D0 → 本人判断と安全条件を満たすCPU/診断整理 → D1〜DnのGPUリファクタ → full-pipeline Mesa CIの順序を維持する。診断共有部と試験の先行移動は削除に必要な限定作業であり、D1の実graph再設計を前倒しする理由にはしない。

SIGMA fp 24MPの実写確認は、本人がdev.2で普段どおり数枚を取り込み・編集・保存して試す。PC接続のRAM計測は失敗・違和感があった場合に段取りし、普段使い確認やD0準備の常時必須ゲートにはしない。これは `Original24MemoryPolicy.kt` / [メモリ検証手順](PREVIEW_MEMORY_PROBE.md) の768MiB条件・予算の変更でも、未測定メモリの合格宣言でもない。実写結果と #371/#372 の合成24MP試験は別の証拠として記録する。

**削除候補の順序と止める条件**（コードは特記しない限り `app/src/main/java/dev/cinematic/p0/` 相対。各段の実行は後続PRで承認範囲と最新mainを確認する）:

| 順 | 編集・削除候補の具体的な入口 | 残すもの・着手前の条件 |
| --- | --- | --- |
| 準備 | `gpu/ShaderConstants.kt`、`raw/R7BaseV1.kt`、`raw/StageClearV1.kt`、`raw/StageClearGlowV1.kt`、`raw/StageClearMistV1.kt`、`raw/StageClearOrtonV1.kt`、`raw/StageClearCharacterV1.kt` | 定数の単一正本と参照先を確定する。V1定義とCPU実行が同居するファイルは丸ごと削除不可。値・型・配列順・生成define、修正後GPU基準を保持。実際の抽出は後段Bの限定PR |
| 1 | `photoroll/ManualTrialPhotoRollRenderer.kt` のCPU draft/exact分岐、`StageClearTrialPipeline.kt` / `StageClearTrialRenderer.kt` のCPU描画 | GPU入口、AS_SHOT、同写真/調整値、取消、比較表示、owner終了を保持。`StageClearTrialPipelineTest` とpreview試験の保証を対応付けてから分岐単位で削除。Trialという名前だけで編集UIを消さない |
| 2 | `raw/original/OriginalStageClear24.kt` のCPU実行、`jpegrender/original/Original24Attempt.kt` のCPU renderer/pack分岐、`jpegrender/JpegGenerationAttempt.kt` の旧producer | native decode、geometry、原本照合、Bitmap/JPEG入口、cleanupを保持。保存authority/engine/codecに接する変更は #365 の提示・実装・検証後に再照合。旧CPU互換の証拠が不足すればこの段を止める |
| 3 | `gpu/DevelopEngine.kt` の診断選択UI・設定読書きとCPU新規選択入口 | `DevelopGpuAvailabilityCache` / 能力検査、GPU失敗理由、owner固定engineは保持。enumや既定引数の除去は呼出し元・旧互換を照合して別判断。`develop/engine` の設定キー撤去を保存データ消去へ広げない |
| 4 | `app/src/rawDiagnostic/`、`app/src/previewDiagnostic/`、`app/src/original24Probe/`、`rawprobe/` の各host・manifest・配線 | variantごとに対象を確定する。rawDiagnosticはgpuDiagnosticから参照されるため共有排他/入力/観測を先に分離。LibRaw/JNI製品基盤、原本/24MP/取消試験は残す。rawprobeは `settings.gradle.kts`、`app/src/main/cpp/CMakeLists.txt` の `CINEMATIC_ENABLE_RAW_PROBE`、`rawprobe/src/main/cpp/baseline_small.cpp`、`app/src/main/cpp/probe.exports` と結合する。appへのJNI非混入検査の代替が先 |
| 5 | `app/src/gpuDiagnostic/` と関連 `app/build.gradle.kts` source set / variant、CI・診断runbook | 下表の試験を通常試験側へ移し、host・manifest・opt-in・実行件数を確認した後。`RawDiagnosticActivity.activeOrHeld()` への呼出しを単に消して排他を弱めない。`gpu/diag/GpuStageTap.kt` とFULL_RECOMPUTEは保持 |
| 6 | 上の #231 対応表にある旧spec/plan/tasks・CPU並列候補・診断手順 | コード/試験の移行を反映してから、段落単位の移管先・未採用理由・被リンク・過去の失敗参照を残す。旧ディレクトリ単位の一括削除や、現行手順のない履歴化をしない |

**GPU共有として残す具体ファイル**: `raw/LinearSrgbImage.kt`（`LinearSrgbImage` / `FloatPlane` 型）、`PhotoAdjustments.kt`、`R7PreviewInputs.kt`（入力解析）、`StageClearColor.kt`（GPU入力準備でも利用）、`StageClearBlur.kt`（filter/係数）、上記V1定数、`raw/original/OriginalRaster.kt` / `OriginalFields.kt` / `NativeOriginalRaster.kt`、`jpegrender/original/Original24PreviewSession.kt` のAS_SHOT経路。`StageClearGpu.kt`、`GpuExportRenderer.kt`、`GpuExportField.kt`、`R7Gpu.kt`、`GpuResample.kt` が参照する。共有型・解析・係数とCPU画素実行を同一視しない。ワイルドカードimportもあるためimport一覧だけで「参照0」を判定しない。

**試験移行の対応表**（現時点では移行先候補、移行成功の記録ではない。以下の短い名前は `.kt`。`gpuDiagnosticTest` のルートは `app/src/gpuDiagnosticTest/java/dev/cinematic/p0/`）:

| 現在のファイル群・保証 | 移行先候補 / 保持と確認 |
| --- | --- |
| `gpuDiagnosticTest/gpu/` の `GpuDiagnosticDngInputTest`、`GpuDiagnosticDngPipelineTest`、`GpuDiagnosticFilesSelectionTest`、`GpuDiagnosticPickerLifecycleTest`、`GpuDiagnosticSurfaceInputTest`、`GpuPreviewLifecycleTest` | `app/src/androidTest/java/dev/cinematic/p0/gpu/` と必要最小限のtest host。入力・選択世代・lifecycle・Surface/GL所有のassertを残す。`src/gpuDiagnosticTest/AndroidManifest.xml` の必要component、非公開属性、intent/permissionを明示移管し、通常releaseに入れない。`GpuPreviewDiagnosticActivity` 等への起動/入力依存があるので単なるクラス移動では成立しない |
| `gpuDiagnosticTest/jpegrender/original/` の `GpuExportBoundaryFixture`、`Original24GpuBoundaryCancellationAndroidTest`、`Original24GpuObserverFailureAndroidTest`、`GpuConcurrentMemoryProbeTest`、`EditorPreviewParallelTest` | 通常 `androidTest` の同packageへ候補分離。合成DNG・故障注入・解放/held状態・観測失敗・保存中の次写真を保持。メモリ観測のopt-inとwall/texture/PSSの区別を保持。移動を実機RAM計測の常時義務にしない |
| `gpuDiagnosticTest/photoroll/` の `GpuExportActiveOwnershipAndroidTest`、`GpuExportSafetyLifecycleAndroidTest`、`GpuProductLifecycleAndroidTest`、`GpuSaveLifecycleAndroidTest`、`CpuExportPhotoSwitchAndroidTest` | 通常 `androidTest/photoroll/` へ候補分離。FGS、保存中編集/選択、旧owner終了、取消/retry/HOLDを対応付ける。CPU専用試験を単純削除せず、安全assertのGPU相当試験と旧CPU互換fixtureのどちらへ残すか決める |
| `gpuDiagnosticTest/raw/` の `GpuDiagnosticLoadTest`、`GpuDiagnosticProbeTest`、`PreviewParallelDiagnosticTest`、各診断host専用試験 | UI/host専用の廃止候補と、排他・原本・資源の保持assertを分割する。hostの存在だけを試すassertはhost削除と同じPRで理由を記録。一般安全assertの削除を同伴させない |
| `app/src/androidTest/java/dev/cinematic/p0/gpu/reference/` の `KotlinReference`、`GpuReferenceImages`、`ImageDiff`、`GpuTolerances` と `GlowMistParityAndroidTest` / `GpuFinishAndroidTest` / `GpuR7AndroidTest` 等 | CPU差は参考値のまま、合成fixture、finite/shape・copy・cache/fresh・GPU同士のassertを残す。CPU実行の削除前に必要な参照実装をtest専用へ隔離するか、同じ保証を持つ代替へ対応付ける。CPU/goldenカバレッジを移行前に失わない |
| `app/src/test/java/dev/cinematic/p0/raw/` と `raw/original/` のCPU/共有試験、特に `R7PreviewInputsTest`、`StageClearBlurTest`、`OriginalParallelRowsTest` | 共有解析/係数、owner終了・解放を継続。#369のexecutor終了とJava thread生存の区別、残留interruptの決定的注入を保持。CPU画素の期待値だけを保証する試験は参照用の保存先と代替を別記 |
| 通常 `androidTest` の `Original24GpuInputAndroidTest` / `Original24GpuSaveInstrumentationTest`、`Original24CancellationRetryInstrumentationTest`、原本/取込み/保存試験、`gpu/golden/GpuGoldenAndroidTest` | 現在の配置を維持。ただし通常androidTestにもpackage/BuildConfig/assume条件があり、`DevelopEngineAndroidTest`、`GpuGate1Part2TimingAndroidTest`、`Original24GpuOutputAndroidTest` / `SafetyInstrumentationTest`、`Original24PreflightInstrumentationTest`、`GpuProductInputAndroidTest`、`PreviewRawGestureFixtureAndroidTest` 等を横断監査し、variant廃止で黙ってskip化させない。#371/#372の24MP合成・実factory/JPEG/publicationと、#370/#373/#374のSAF/通常UI/FGSを診断UIと一緒に除去しない。採用90条件と実行環境・hashも保持 |

移行PRには「元のクラス/試験名 → 保持するassert → 新しいhost/クラス/実行引数 → 実行件数・成功/失敗/skip → 旧配線を外してよい根拠」を載せる。CIのコンパイルだけでAndroid実行済みにせず、欠落・0件・skipを合格にしない。`.github/workflows/android.yml` の製品、gpu/raw診断、rawprobe、JNI隔離、Mesaと必須集約を、代替の保証を確認せず減らさない。CPU/診断整理後もAndroidの全工程回帰と所有/保存安全試験を維持し、full-pipeline Mesa CI完成で置き換える層とAndroidに残す層を分ける。

**#231の残資料の追跡**: `specs/015-stage-clear-android/` の `preview-inputs.md` に加え `buffer-memory.md`、`dng-input.md`、`r7-base.md`、`w1-trial.md`、`white-xy-compat.md` を個別に照合する。入力/メモリ/色の未集約契約はdevelop/storageの仕様と既存DNG/GPU設計へ、実行手順は対応診断docsへ、当時の採否/失敗はIssue/PR参照へ分ける。Living Specから016/036/037/038/043/047/051/056/058等への直接リンクも残っているため、移管先の段落と参照更新を確認するまで旧資料を保持する。#365対象の保存文書はその確定契約を待ち、並行して別の復旧仕様を作らない。

**#365と旧保存の互換準備**: `rawcapture/ManualOriginalTrialRequests.kt` のcodec v2にengineはない。`AndroidManualOriginalTrialSaves.kt` のプロセス内 `selectedEngine` から再起動後engineを復元したことにしない。#365の新しい証明形式・engine/version/attempt・失効順は同Issueの設計/実装で確定する。本準備はv2をGPUへ読み替えず、次のfixture群を後続PRへ要求する。

- v2既存bytes、未知/破損形式、旧CPU由来でengine不明: 自動GPU再生成なし。intent-onlyはHOLD、publication UNKNOWNはUNCONFIRMED/同一publication再照合。
- 同プロセスの確認済み取消/producer failure、同設定retry、新recipe受付、JPEG入口前後、各cleanup失敗: 現行 #357 の条件を保持。再起動許可を現行実装済みに数えない。
- 完成JPEG/proof/publication、原本改変/権限失効、世代commit不明、二重prepare: 同じ成果物の再照合と原本/旧journal保持、二重producer/公開の拒否。
- #365が追加する取消証明のwrite/readback前後、retry前の失効/STARTED前後、process death: 同Issueで実装範囲を提示し検証してから、CPU削除・保存refactorへの影響を再照合。古いintentの自動解除や別書出しUXをD0に混ぜない。

準備の完了は一覧・参照根拠・移行案がレビュー可能になったことまで。残る判断は本人の削除可否、普段使いで発生した問題の扱い、#365の提示範囲と実装結果、定数の正本抽出、旧CPU互換・各試験の移行完了である。#231の未監査資料は保持し、#290/#231をD0文書だけで閉じない。

### 4.12 失敗時の扱い

- GPU能力不足・context喪失・予算超過等は見える失敗にし、**CPUへ自動fallbackしない**。previewは選択写真の画像領域に理由と明示retryを示し、旧owner終了確認と原本再確認後だけ新sessionを開く。
- 保存は固定理由/当該ownerの回数を表示する。JPEG入口前のGPU producer failureでGPU・原本・作業領域・出力の全cleanupが確認できた時だけ、同じsource/recipe/generation/engineで明示retryする。失敗中の別編集世代・別engineへの迂回はしない。
- #346の同世代明示retryを維持し、#357ではJPEG入口前・全cleanup完了が確認できた同プロセスの取消ownerに限り、明示的な設定変更による新世代も許す。旧intentと一致しpublicationがないことを受付時に再確認し、新requestの確定で旧世代を失効させる。解放不明・JPEG入口後・restart・intent不一致はHOLD/既存publication再照合を維持する。durable intentを消して回避しない。
- 原本の強い照合、Foreground Service、既存publicationは共用する。画面の取消/失敗表示とowner回収を分け、実施していない故障注入をGate 2承認へ含めない。

### 4.13 切り替えフラグ

- `DevelopEngine { CPU, GPU }` をpreviewとexportで共用し、保存は最初の実保存時にengineを固定する。
- 通常debug/releaseの新規プレビュー・保存はGPU固定。診断用保存値は読まず、設定UIとCPU自動回避は作らない。gpuDiagnosticは明示CPU/GPU選択、その他診断variantと低水準試験はCPU既定を維持する。保存ownerの固定engineと既存データは移行しない。
- CPU既定への復帰は想定しない。問題時はレビュー済み修正または既知正常のGPUソースを、同じ署名・より大きいversionCodeの新ビルドで配布する方法を検討する。データ互換を確認し、配布は都度本人承認を得る。旧APKへのdowngradeや内部データを失うアンインストールで回避しない。
- Gate 2本人承認とGPU既定化PRのマージ承認は別であり、マージ前に本人確認する。GPU基準画像のAndroid先行試験は既定切替を待たず並行して進め、採用は本人の目視確認後とする。

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
