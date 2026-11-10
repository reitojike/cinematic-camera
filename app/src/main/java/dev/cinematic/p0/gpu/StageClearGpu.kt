package dev.cinematic.p0.gpu

import android.opengl.GLES32
import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.gpu.look.StageClearFinishGpu
import dev.cinematic.p0.gpu.look.StageClearGlowGpu
import dev.cinematic.p0.gpu.look.StageClearMistGpu
import dev.cinematic.p0.gpu.preview.present
import dev.cinematic.p0.gpu.r7.R7Gpu
import dev.cinematic.p0.gpu.resample.GpuResample
import dev.cinematic.p0.raw.LinearSrgbImage
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.raw.R7PreviewInputs
import dev.cinematic.p0.raw.StageClearColor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

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

/** #284 の表示寸法。window の寿命・swap・GPU query は renderer が所有する。 */
internal data class StageClearGpuPreviewTarget(
    val window: EglContexts.WindowTarget,
    val width: Int,
    val height: Int,
    val linearFiltering: Boolean = false,
) : GpuPreviewTarget {
    init { require(width > 0 && height > 0) }
}

/**
 * 写真ごとの R7/Glow 保持と一時出力を所有する接続点。入力は借用し、close しない。
 * tap/consume は同じ GL thread 上の同期 callback。渡す texture は callback 中だけ借用する。
 * 診断のtap指定は全工程を再計算する。通常描画の強さ変更ではR7/Glowの保持を使う。
 */
internal interface StageClearGpuPipeline : AutoCloseable {
    fun render(params: GpuRenderParams, tap: ((GpuStageTap.Point, GpuTexture) -> Unit)? = null,
        consume: (GpuTexture, GpuDecisions) -> Unit)
}

/**
 * 公開 API の写真・入力・解析・表示の所有者。EGL/ledger は renderer から借用する。
 * INPUT/Beforeはルックを通さず、同じ常駐AS_SHOTから描画する。
 */
internal class DefaultStageClearGpu(
    private val egl: EglContexts,
    private val ledger: GpuMemoryLedger,
    private val createPipeline: (GpuTexture, GpuPhotoAnalysis) -> StageClearGpuPipeline = { input, analysis ->
        ConnectedStageClearGpuPipeline(input, analysis)
    },
) : StageClearGpu {
    private val photos = mutableSetOf<Photo>()
    private var closed = false
    private var capabilitiesChecked = false

    override fun openPhoto(input: LinearSrgbImage, stop: () -> Boolean): StageClearGpuPhoto {
        requireOpen()
        checkpoint(stop)
        if (!capabilitiesChecked) {
            GlCapabilities.inspect(egl).requireSupported()
            capabilitiesChecked = true
        }
        // Meter the unmodified AS_SHOT exactly once, before warmth and user EV.
        val prepared = R7PreviewInputs.prepare(R7PreviewInputs.AsShotInput(input,
            R7PreviewInputs.Source.LIBRAW_PRELIMINARY_AS_SHOT), emptyList(), stop)
        val analysis = GpuPhotoAnalysis(prepared.exposure.externalBaseEv, prepared.exposure.centralP97)
        checkpoint(stop)
        val texture = GpuTexture.create(egl, ledger, input.width, input.height, GpuTexture.Format.RGBA32F)
        try {
            val rgba = ByteBuffer.allocateDirect(Math.multiplyExact(input.pixelCount, 16))
                .order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (y in 0 until input.height) {
                checkpoint(stop)
                for (x in 0 until input.width) {
                    val i = (y * input.width + x) * 3
                    rgba.put(input[i]); rgba.put(input[i + 1]); rgba.put(input[i + 2]); rgba.put(1f)
                }
            }
            rgba.flip(); texture.upload(rgba)
            checkpoint(stop)
            return Photo(texture, analysis).also { photos += it }
        } catch (failure: Throwable) {
            runCatching { texture.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    override fun renderPreview(photo: StageClearGpuPhoto, params: GpuRenderParams,
        target: GpuPreviewTarget, display: GpuDisplayOptions): GpuFrameStats {
        val source = requirePhoto(photo)
        requireParams(params)
        require(display.rotationDegrees in setOf(0, 90, 180, 270))
        val destination = requireNotNull(target as? StageClearGpuPreviewTarget) { "GPU preview needs window dimensions" }
        egl.makeCurrent(destination.window)
        val start = System.nanoTime()
        var decisions = source.lastDecisions
        fun show(image: GpuTexture) {
            image.requireContext(egl)
            require(image.memoryLedger === ledger) { "GPU frame belongs to another memory ledger" }
            present(egl, image, destination.width, destination.height, display.rotationDegrees,
                linearFiltering = destination.linearFiltering)
        }
        if (display.showBefore) show(source.input)
        else {
            var consumed = false
            source.pipeline().render(params) { image, current ->
                check(!consumed) { "GPU pipeline emitted multiple frames" }
                require(image.width == source.width && image.height == source.height && image.format == GpuTexture.Format.RGBA32F)
                require(current.mistAutoStrength.isFinite() && current.mistAutoStrength in 0.0..1.0)
                require(current.faceCutFactor == 1.0) { "Phase 0 does not implement face cut" }
                show(image)
                source.lastDecisions = current
                decisions = current
                consumed = true
            }
            check(consumed) { "GPU pipeline did not emit a frame" }
        }
        // The renderer times the whole submitted frame with its existing nonblocking GPU query.
        return GpuFrameStats(null, System.nanoTime() - start, decisions)
    }

    override fun renderForTest(photo: StageClearGpuPhoto, params: GpuRenderParams,
        tap: GpuStageTap.Point): LinearSrgbImage {
        val source = requirePhoto(photo)
        requireParams(params)
        if (tap == GpuStageTap.Point.INPUT) return GpuStageTap.readRgb(egl, source.input)
        var result: LinearSrgbImage? = null
        source.pipeline().render(params, tap = { point, texture ->
            if (point == tap) {
                check(result == null) { "GPU pipeline emitted duplicate $tap" }
                result = readTap(texture)
            }
        }) { _, _ -> }
        return checkNotNull(result) { "GPU pipeline did not emit $tap" }
    }

    private fun readTap(texture: GpuTexture): LinearSrgbImage = when (texture.format) {
        GpuTexture.Format.RGBA32F -> GpuStageTap.readRgb(egl, texture)
        GpuTexture.Format.R32F -> {
            val plane = GpuStageTap.readPlane(egl, texture)
            LinearSrgbImage.takeOwnership(texture.width, texture.height,
                FloatArray(texture.width * texture.height * 3) { plane[it / 3] })
        }
        GpuTexture.Format.RGBA8 -> {
            texture.requireContext(egl)
            GpuFence(egl).use { it.await() }
            val bytes = ByteBuffer.allocateDirect(texture.width * texture.height * 4)
            GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, texture.framebufferId)
            GLES32.glReadBuffer(GLES32.GL_COLOR_ATTACHMENT0)
            GLES32.glBindBuffer(GLES32.GL_PIXEL_PACK_BUFFER, 0)
            GLES32.glPixelStorei(GLES32.GL_PACK_ALIGNMENT, 1)
            GLES32.glPixelStorei(GLES32.GL_PACK_ROW_LENGTH, 0)
            GLES32.glPixelStorei(GLES32.GL_PACK_SKIP_PIXELS, 0)
            GLES32.glPixelStorei(GLES32.GL_PACK_SKIP_ROWS, 0)
            GLES32.glReadPixels(0, 0, texture.width, texture.height, GLES32.GL_RGBA, GLES32.GL_UNSIGNED_BYTE, bytes)
            egl.checkGl("read DISPLAY8 tap")
            // Preserve the public linear-RGB return type. Re-encoding these quantized values
            // recovers the exact display bytes; never label normalized sRGB bytes as linear RGB.
            LinearSrgbImage.takeOwnership(texture.width, texture.height,
                FloatArray(texture.width * texture.height * 3) { i ->
                    StageClearColor.unitToLinear((bytes.get(i / 3 * 4 + i % 3).toInt() and 255) / 255f)
                })
        }
        else -> error("Unsupported stage tap format: ${texture.format}")
    }

    private inner class Photo(val input: GpuTexture, override val analysis: GpuPhotoAnalysis) : StageClearGpuPhoto {
        override val width = input.width
        override val height = input.height
        // Before bypasses the look; retain the last computed decision rather than evaluating it.
        var lastDecisions = GpuDecisions(0.0, 1.0)
        private var passes: StageClearGpuPipeline? = null
        private var photoClosed = false
        fun requireOpen() { check(!photoClosed && this in photos) { "GPU photo is closed or foreign" }; input.requireContext(egl) }
        fun pipeline(): StageClearGpuPipeline {
            requireOpen()
            return passes ?: createPipeline(input, analysis).also { passes = it }
        }
        override fun close() {
            egl.glThread.requireCurrent()
            if (photoClosed) return
            // Preserve failed owners for an explicit cleanup retry. Never release borrowed input
            // while its pipeline still has an unconfirmed close.
            passes?.close(); passes = null
            input.close()
            photoClosed = true; photos.remove(this)
        }
    }

    private fun requireOpen() { egl.requireCurrent(); check(!closed) { "Stage Clear GPU is closed" } }
    private fun requirePhoto(photo: StageClearGpuPhoto): Photo {
        requireOpen()
        val owned = requireNotNull(photo as? DefaultStageClearGpu.Photo) { "Foreign GPU photo" }
        check(owned in photos) { "Foreign GPU photo" }
        owned.requireOpen()
        return owned
    }
    private fun requireParams(params: GpuRenderParams) {
        require(params.strength.isFinite() && params.strength in 0.0..1.0)
    }
    private fun checkpoint(stop: () -> Boolean) {
        if (stop()) throw CancellationException("GPU photo preparation stopped")
    }
    override fun close() {
        egl.glThread.requireCurrent()
        if (closed) return
        var failure: Throwable? = null
        photos.toList().forEach { photo ->
            try { photo.close() } catch (problem: Throwable) {
                if (failure == null) failure = problem else failure!!.addSuppressed(problem)
            }
        }
        failure?.let { throw it }
        closed = true
    }
}

/** Photo-local connections. Cache owners outlive frames; frame outputs never outlive consume. */
internal class ConnectedStageClearGpuPipeline(
    private val input: GpuTexture,
    private val analysis: GpuPhotoAnalysis,
) : StageClearGpuPipeline {
    private val egl = input.context
    private val ledger = input.memoryLedger
    private val r7 = R7Gpu(egl, ledger)
    private val resample = GpuResample(egl, ledger)
    private val finish = StageClearFinishGpu(egl, ledger)
    private var glow: StageClearGlowGpu.Prepared? = null
    private var adjustments: PhotoAdjustments? = null
    private val transient = linkedSetOf<GpuTexture>()
    private var closed = false
    internal val r7FragmentPassCount get() = r7.fragmentPassCount
    internal var glowPreparationCount = 0; private set
    internal var lastDecisions = GpuDecisions(0.0, 1.0); private set

    override fun render(params: GpuRenderParams, tap: ((GpuStageTap.Point, GpuTexture) -> Unit)?,
        consume: (GpuTexture, GpuDecisions) -> Unit) {
        input.requireOpen(); check(!closed) { "Stage Clear pipeline is closed" }
        require(params.strength.isFinite() && params.strength in 0.0..1.0)
        releaseTransient()
        // Glow borrows R7_FINAL. Release it BEFORE any R7 recomputation, including diagnostic taps.
        if (adjustments != params.adjustments || tap != null) {
            glow?.close(); glow = null; adjustments = null
        }
        val base = r7.render(input, params.adjustments.warmth,
            analysis.meteredBaseEv + params.adjustments.exposureEv, foundationRoi = null, tap = tap)
        val prepared = glow ?: StageClearGlowGpu.prepare(base, resample).also {
            glow = it; adjustments = params.adjustments; glowPreparationCount++
        }
        var failure: Throwable? = null
        try {
            tap?.invoke(GpuStageTap.Point.SKIN_WEIGHT, prepared.skinWeight)
            val glowed = prepared.render(params.strength).also { if (it !== base) transient += it }
            tap?.invoke(GpuStageTap.Point.GLOW, glowed)
            val auto = StageClearMistGpu.autoStrength(glowed)
            val mist = StageClearMistGpu.render(glowed, prepared.skinWeight, params.strength,
                resample, autoStrength = auto).also { if (it.image !== glowed) transient += it.image }
            // Mist may borrow base at strength zero; otherwise its new image replaces Glow.
            if (mist.image !== glowed) release(glowed)
            tap?.invoke(GpuStageTap.Point.MIST, mist.image)
            val shoulder = finish.render(mist, params.strength, faceMask = null, tap = tap)
                .also { transient += it }
            release(mist.image)
            // Normal preview goes straight to the window, with no RGBA8 allocation/readback.
            if (tap != null) {
                val bytes = finish.encode(shoulder).also { transient += it }
                tap(GpuStageTap.Point.DISPLAY8, bytes)
                release(bytes)
            }
            val decisions = GpuDecisions(auto, 1.0)
            consume(shoulder, decisions)
            lastDecisions = decisions
        } catch (problem: Throwable) {
            failure = problem
            throw problem
        } finally {
            try { releaseTransient() } catch (cleanup: Throwable) {
                if (failure == null) throw cleanup
                if (failure !== cleanup) failure.addSuppressed(cleanup)
            }
        }
    }

    private fun release(texture: GpuTexture) {
        if (texture in transient) { texture.close(); transient.remove(texture) }
    }
    private fun releaseTransient() {
        var failure: Throwable? = null
        transient.toList().asReversed().forEach { texture ->
            try { release(texture) } catch (problem: Throwable) {
                if (failure == null) failure = problem else failure!!.addSuppressed(problem)
            }
        }
        failure?.let { throw it }
    }
    override fun close() {
        egl.glThread.requireCurrent()
        if (closed) return
        // Keep an owner if close fails, so the photo/engine can explicitly retry cleanup.
        releaseTransient()
        glow?.close(); glow = null; adjustments = null
        finish.close(); resample.close(); r7.close()
        closed = true
    }
}
