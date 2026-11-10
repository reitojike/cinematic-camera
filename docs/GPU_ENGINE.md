# GPU 描画エンジンの契約

[親 Issue #276](https://github.com/reitojike/cinematic-camera/issues/276) §4 と
[契約 Issue #277](https://github.com/reitojike/cinematic-camera/issues/277) PR1 を転記した設計の正本。
本 PR のマージまでは提案であり、マージ後はこの文書を API・GLSL・実装設計の正本とする。
進捗・検証結果・採否は Issue / PR に記録する。製品の既定は Gate 2 の承認まで CPU のまま。
以下の §4 は親 Issue の節番号・本文を維持する。

## 4. 詳細設計

### 4.1 パッケージ・ファイル構成

すべて新規。既存ファイルの変更は §5 の各 Issue で明示したものに限る。

```
app/src/main/java/dev/cinematic/p0/gpu/
  GlThread.kt              専用スレッドとキュー。GL 呼び出しはすべてこのスレッドで行う
  EglContexts.kt           EGLDisplay、共有コンテキスト、オフスクリーン（1x1 pbuffer）、画面用 EGLSurface
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

app/src/androidTest/java/dev/cinematic/p0/gpu/   CPU 基準との一致試験（§4.11）
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
    /** 写真ごとに1回求める値。プレビューの描画中は固定。 */
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

- **API**: OpenGL ES 3.2（Compute シェーダーを含む）。Android 標準 API であり、新しい依存関係は加えない。
- **必須の能力**（`GlCapabilities` で起動時に検査し、満たさなければ GPU 経路を使わない）:
  - `GL_EXT_color_buffer_float`（RGBA32F/R32F へ描画）
  - 最大テクスチャサイズ ≥ 4096
  - Compute シェーダー（ES 3.1 以上）
- **精度**: シェーダーは `precision highp float;`。中間テクスチャは RGBA32F（色）・R32F（平面）を既定とする。RGBA16F は Gate 1 の計測で差が基準内と確認できたものだけに使う。
- **スレッド**: GL の呼び出しはすべて `GlThread` 上。UI スレッドからは要求をキューに積むだけ。要求は**最新の1件だけ**を残して合流する（Choreographer のフレームごとに1回描画）。
- **コンテキスト**: 1つの共有コンテキストを持ち、プレビューの画面用サーフェスと書き出しのオフスクリーンで共有する。プレビューと書き出しが同時に走る場合、書き出しは帯ごとに区切って GlThread のキューに積み、プレビューのフレームを優先する。
- **メモリ**: `GpuTexture` がバイト数を記録し、合計を `GpuMemoryLedger` に報告する。目安はプレビュー 96MiB 以下、書き出し 192MiB 以下（12MP）。超える要求は失敗させる（黙って縮小しない）。`peakAllocatedBytes` は成功した予約の最大値を解放後も保持する。AS_SHOT を含む全工程の最大と予算との差を計測する。予算変更の判断と Gate 1 基準は [#276](https://github.com/reitojike/cinematic-camera/issues/276)・[#285](https://github.com/reitojike/cinematic-camera/issues/285) に記録する。
- **解放**: 写真を閉じる、画面を離れる、`onTrimMemory`（`TRIM_MEMORY_UI_HIDDEN` 以上）で写真の GPU 状態を解放する。コンテキスト喪失（`EGL_CONTEXT_LOST`）は失敗として扱い、次の要求で作り直す。

### 4.4 色と座標の約束

- 入力・中間は線形 sRGB（D65）、シーン線形。クリップや転送は Kotlin が行う箇所だけで行う。
- 画素中心は `(x + 0.5, y + 0.5)`。`texelFetch` の整数アドレスと Pillow の明示的な重みを使う。
- 行列の乗加算順序と各軸間の Float 丸めを保つ。Kotlin の見本の計算は変更しない。
- 2026-11-10 の利用者判断（#279 / PR #305）により、`cs_signedCbrt` は pow の seed に precise 付き Newton 法を1回適用する。有限極値で cube が overflow、または分母が0の場合は有限 seed を保持する。0は0を返す。
- 同判断で色相補正 B2 を採用する。元の `atan(b, a)` を seed とし、方向 `(a,b)` を `frexp` の仮数と共通指数の差による `ldexp` で正規化してから sin/cos の残差で1回補正する。正角度へ不要な360加算をせず、負角度だけ正規化する。符号付き両ゼロの規約を維持する。
- MAX 値による除算は指定 Android で両成分が0となったため使わない。GLSL ES §8.3 は `ldexp` の指数>+128を未定義、指数<-126を0へflush可とするので、単純な `ldexp(x,-最大指数)` も使わない。仮数から共通指数との差（非正）で揃えれば支配成分の指数差は0となり、方向を失わない。ゼロ成分の指数0は共通指数の選択から除外し、正の指数差も0で抑える。
- 色相の利用側は円周上の距離を使う。試験も `min(abs(ref-actual) % 360, 360 - abs(ref-actual) % 360)` で比較し、0°と360°を同じ角度とする。線形のULP距離は診断値であり、色相の合否には使わない。

工程専用の許容差は利用者判断時に次の値で固定し、結果を見て追加緩和しない。正本の定数は `GpuTolerances` に置く。

| 工程 | 固定した受入条件 |
| --- | --- |
| RGB→Lab、Lab→RGB、および往復 | 参照の絶対値≤1では絶対差≤4e-6、絶対値>1では相対差≤2e-5 |
| 色相 | 円周上の絶対差≤1e-4度 |
| 転送、立方根単体、band | 従来どおり絶対差≤2e-6 / 相対差≤1e-5 |
| skinWeight | 従来どおり絶対差≤1e-5 |
| plane/RGB blur、BICUBIC | #279 の従来基準を維持 |

根拠: [GLSL ES 3.20.8 §4.7.1](https://registry.khronos.org/OpenGL/specs/es/3.2/GLSL_ES_Specification_3.20.html) は、pow の精度要件を `exp2(y * log2(x))` から継承させ、三角関数の精度を同表で定義しない。subnormal の0へのflushも許す。powが必ずその式で実装されるという規定ではなく、Math.cbrt/atan2 と同じ丸めを保証するものでもない。
今回の閾値は規格から得られる保証値ではなく、測定と利用者承認に基づく契約である。Newton後の往復最大差は Android 2.682209e-6 / Mesa 2.592802e-6、B2後のMesa色相最大差は約3.05176e-5度だった。最終の exact head を両環境で検証して記録する。

**全工程の基準は変えない。** #276 の表示8bit最大差2・99.9パーセンタイル差1、Mist自動強度0.005、局所分位点1e-4を #284 の全工程で確認する。単体の表示差やエミュレーター成功を最終画質・実機速度の合格へ読み替えない。
### 4.5 Pillow 互換のリサンプル（GPU）

`StageClearBlur.resize` と `OriginalField` が真実の源。GPU 版は次の通りに実装する。

1. **重みの計算は CPU（Kotlin）で行う。** `StageClearBlur.samples(inputSize, outputSize, filter)` をそのまま呼び、各出力位置の `first` と正規化済み重みを、R32F のテクスチャ（行=出力位置、列=タップ）と R32I（`first`）にしてアップロードする。重みは画像サイズと半径だけで決まるので、写真ごとにキャッシュする。
2. **軸の順番**は `StageClearBlur.resize` と同じ規則（`verticalFirst` の条件）で決める。軸ごとに1パス。
3. 各パスは「出力1画素 = Σ 入力[first+i] × w[i]」。BOX 縮小は1出力あたりのタップ数が多い（例: 1024px で半径 7% なら約70タップ）ため、**縮小パスは Compute シェーダー**で、行（列）単位にワークグループを割り当て、共有メモリで部分和を取る。拡大（BILINEAR 2〜3タップ、BICUBIC 4〜5タップ）はフラグメントシェーダー。
4. 軸と軸の間で値を単精度に丸める（Pillow と同じ）。累積は単精度（Kotlin は倍精度。差は許容範囲で吸収）。
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
| Glow | `StageClearGlow.addLight`・`renderNonzero` | source → bloom 3半径＋halation のぼかし → 合成 → diffusion のぼかし → 合成 | 強さ非依存の場（skin・halo・bloom）は写真×調整値ごとにキャッシュ（`PreparedStageClearGlow` と同じ範囲） |
| Mist 自動強度 | `StageClearMist.nearWhiteFraction`・`autoStrength` | 数え上げ（Compute、§4.7） | プレビューは描画解像度で求める |
| Mist | `StageClearMist.applyMist`・`render` | source → 2半径のぼかし → 合成 | |
| Orton | `StageClearOrton.mixOrton`・`applyOrton`・`render` | encode/bright → ぼかし → mix → 彩度保護 → 強さで混合 | |
| Character | `StageClearCharacter.vignette`・`vibrance` | 1パスに融合 | |
| skinChromaCap | `StageClearProtections.skinChromaCap` | 1パス | 顔マスクは現状すべて 0。uniform で 0 を渡せる形にしておく |
| face cut | `StageClearProtections.faceCut` | Phase 0・1 では実装しない | 製品の顔マスクが NONE のため発生しない。インターフェースだけ用意し、呼ばれたら失敗させる |
| shoulder | `StageClearProtections.shoulder` | 1パス | |
| 表示 | `TrialDisplayPixels.byte` | 1パス（sRGB エンコード、8bit 丸め） | 書き出しの `channel()` と同じ式であることを試験で確認 |

### 4.7 統計（写真全体の値）

| 値 | 現行の定義 | GPU 版の方法 | 結果の扱い |
|---|---|---|---|
| 自動露出 p97 | 中央 1/2×1/2 の輝度、NumPy float32 linear（近い端点から補間） | 入力は CPU にもあるので、**CPU で求める**（`R7PreviewInputs.prepare` をそのまま呼ぶ） | 写真ごとに1回。現行と完全一致 |
| 局所トーン p50/p92 | 原寸に拡大した平滑 log 輝度の全画素 | Compute シェーダーで `OriginalQuantiles` と同じ2段の基数ヒストグラム（上位16bit → 該当バケット内の下位16bit）を作り、厳密に選ぶ | 描画のたびではなく、R7 の入力（warmth・EV）が変わったときだけ。厳密に一致させる |
| 白に近い画素の割合 | Glow 後の線形輝度 > NEAR_WHITE の画素数 | Compute シェーダーで原子的に数える | 強さ・調整値が変わるたび。厳密に一致させる |

### 4.8 書き出し（帯ごとの計算と「場の境界」）

12MP・24MP の中間画像を GPU に丸ごと置かない。工程の途中に、写真全体から作る縮小場が必要になる箇所（**場の境界**）があるため、次のように区切る。

| 境界 | 必要な場 | 境界より前に計算する工程 |
|---|---|---|
| B1 | 局所トーンの格子（BOX 縮小）と p50/p92 | warmth → profile → log 輝度 |
| B2 | 肌の重みの縮小場、Glow の bloom・halation の縮小場 | B1 まで ＋ tone → foundation → vibrance/crisp/skin |
| B3 | diffusion の縮小場 | B2 まで ＋ Glow 合成 |
| B4 | 白に近い画素の数、Mist の縮小場 | B3 まで ＋ diffusion 合成 |
| B5 | Orton の縮小場 | B4 まで ＋ Mist 合成 |
| 最終 | なし | B5 まで ＋ Orton → Character → skinChromaCap → shoulder → sRGB 8bit |

- **各境界で、原寸の入力を帯（例: 高さ 256 行）ごとに最初から計算し直す。** 原寸の中間画像を保存しない代わりに、前の工程を再計算する。GPU では1画素ずつの計算が軽いため、ディスクへの中間ファイル書き出し（現行）より速く、メモリも一定になる。
- 縮小場（BOX）は帯ごとに部分和を加算していく（縮小の重みは軸ごとに分かれているので、帯の行がどの縮小行にどれだけ寄与するかを事前に計算できる）。縮小場は小さい（数十〜数百px四方）ので GPU に常駐させる。
  - 帯ごとの加算は「横に縮小 → 縦に加算」の順になる。Pillow の規則で縦を先にする条件（`verticalFirst`）に当たる場合は、軸の間の単精度への丸めの位置が現行と変わる。この差は §4.11 の許容範囲で判定し、範囲を超える場合は G1-1 で止めて親 Issue に報告する。
- 拡大（BILINEAR/BICUBIC）は、帯の各画素から縮小場を直接サンプリングする。継ぎ目の処理は不要。
- 入力は既存の RGB16 原寸ラスタ（私有ファイル）を帯ごとに読み、R16 系の整数テクスチャとしてアップロードし、シェーダーで float に変換する。
- 最終パスの出力は RGBA8 で帯ごとに読み戻し、既存の Bitmap に `setPixels` で詰め、既存の `Bitmap.compress` で JPEG にする（12MP では現行のまま）。24MP の省メモリ化（libjpeg-turbo による行ごとの書き出し）は Phase 2 で、依存関係の承認を得てから。
- 取消は帯ごとに確認する（取消から停止まで最大で帯1つ分）。
- 原本の照合、保存と公開の記録、前面サービス、`Original24Resources` の確認は既存のまま呼ぶ。`Original24Attempt` の中で `OriginalStageClear24.render` を呼んでいる箇所だけを、フラグで `GpuExportRenderer` に切り替える。

### 4.9 プレビュー（表示と操作）

- **入力解像度**: Phase 0 の GPU プレビューは **長辺 1024px 以下、かつ 786,432画素（1024×768）以下**。撮影で用いる4:3・16:9は従来どおり1024×768・1024×576（縦向きは転置）とし、近正方形だけ面積上限にも収まるよう縦横比を保つ。正方形の例は886×886。
  - 寸法は入力縮小段階で `min(1, 1024/長辺, sqrt(786432/画素数))` の共通倍率と各辺floorで一度決定する。既に両上限内なら寸法を変えない。GPU用DNG入口は既存RGB16→BOX縮小へこの寸法を渡し、通常CPUプレビュー・書き出しの寸法規則は変更しない。
  - rendererとStageClearGpu.openPhotoは、この寸法決定済み入力を受け取る。どちらかの上限を超えた場合は、既存checkと同じ明示失敗にし、GPU内部で再縮小しない。
  - 理由: 長辺だけでは1024×1024も許容され、1024×769のメモリ計測から最大面積の安全性を保証できなかった。撮影の4:3/16:9を維持しつつ、ユーザー判断でGPUプレビューの面積も制限する。preview 96MiB、書き出し192MiB、ルックの式、画素差の基準は不変。
  - 最大メモリの確認形状は1024×768、768×1024、1024×576、886×886。従来1024×769は上限外の履歴比較として旧診断候補でAndroid実測と照合し、現行入力の受け入れに含めない。画面サイズへの拡大は表示パスで行う。
- **表示面**: Compose の `AndroidExternalSurface`（または `AndroidView` + `SurfaceView`）。EGLSurface は表示面の生成・破棄に合わせて作り直す。
- **描画の流れ**: スライダーの値が変わる → `GpuRenderParams` を GlThread のキューに置き換え → 次のフレームで1回描画。
  - 強さだけの変更: R7 の結果と Glow の強さ非依存の場を再利用し、Glow 合成以降だけを計算する。
  - 色温度・明るさの変更: R7 から計算し直す（局所トーンの分位点も求め直す）。
- **二段構えはなくす**: ドラッグ中も指を離した後も同じ正確な描画。256px の粗い描画、古い要求の破棄・取消の仕組みは、GPU 経路では使わない（CPU 経路には残す）。
- **比較表示**: `showBefore = true` のときは AS_SHOT 入力をそのまま sRGB 表示する（現行の比較画像の定義と同じ）。
- **原本の照合**: 写真を開くときと保存の前後で行う（#268 の境界のまま）。フレームごとには行わない（入力は GPU に常駐し、ファイルを読まないため）。
- Phase 0 では製品の画面には組み込まず、診断版の画面で動かす。

### 4.10 計測

- `GpuFrameStats`: CPU 側の投入時間、`GL_EXT_disjoint_timer_query` が使えれば GPU 時間。
- 診断版の画面で、フレーム時間の中央値・p95・最大、メモリ台帳、温度状態を記録する。写真名・写真ごとの数値は Git・Issue・PR に入れない。

### 4.11 正しさの検証

| 種類 | 内容 | 場所 |
|---|---|---|
| 合成画像セット | 決まった乱数で作る合成画像（グラデーション、白飛び、肌色、彩度の高い色、暗部、ノイズ）。256×192 と公開入力上限内の1024×768。単体passの旧1024×769 fixtureは履歴比較として保持 | `androidTest`、`GpuReferenceImages.kt`（新規） |
| 工程ごとの一致 | `GpuStageTap` で各工程の出力を読み戻し、Kotlin の同じ工程（`R7Base.stages()` など既存の関数）と比べる | `androidTest`（エミュレーター。GPU はソフトウェア実装だが正しさの確認には足りる） |
| 最終出力の一致 | 表示 8bit で最大差 ≤2、99.9 パーセンタイル ≤1、平均差を記録 | 同上 |
| 判定値の一致 | Mist 自動強度は差 ≤0.005、局所トーン p50/p92 は単精度の範囲で一致 | 同上 |
| 実写 | ユーザーの写真で、手元だけで比較（非公開の比較ページ、タップで切り替え）。数値・写真は Git に入れない | 実機・PC |

許容範囲を超えた工程は、式の写し間違いとして直す。許容範囲そのものを後から広げない（広げる場合はユーザーの判断として記録する）。

### 4.12 失敗時の扱い（Gate 2 で最終決定）

- 提案: GPU が使えない（能力不足・コンテキスト喪失・メモリ不足）場合、プレビューは「GPU を使えません」の表示と再試行、書き出しは既存の失敗・再試行の流れに乗せる。**黙って CPU 経路に切り替えない**（見た目が変わるため）。
- 代案: CPU 経路に切り替え、その旨を表示する。Gate 2 でユーザーが選ぶ。

### 4.13 切り替えフラグ

- `DevelopEngine { CPU, GPU }` を1つだけ持ち、プレビューと書き出しの両方がこれを見る。
- Phase 0・1 の間は、診断版と開発設定でのみ GPU を選べる。製品の既定は CPU。
- Gate 2 の承認後、既定を GPU に変える PR を別に作る（製品の既定の変更なので、ユーザーの確認を挟む）。

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
