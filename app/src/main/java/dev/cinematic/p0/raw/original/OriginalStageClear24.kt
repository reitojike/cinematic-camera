package dev.cinematic.p0.raw.original

import android.os.Debug
import dev.cinematic.p0.raw.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

/** Spatial masks are caller-supplied observations, not detector/AE claims. Empty explicitly disables them. */
internal fun interface OriginalMask { fun row(y: Int, weights: FloatArray)
    companion object { val NONE = OriginalMask { _, out -> out.fill(0f) } }
}

/** Full-size CPU reference using private row rasters and globally shared analysis/blur fields.
 * Returns pixels only: neither verified input/face/AE metadata nor CompletedRender/publication authority. */
internal class OriginalStageClear24(private val work: OriginalRasterWorkspace, private val trace: OriginalStageTrace? = null,
    private val parallelOrtonWorkers: Int = 1,
    private val ortonRowProbe: ((Int) -> Unit)? = null) {
    private val luma = StageClearV1.LUMA
    private fun y(row: FloatArray, i: Int) = row[i] * luma[0] + row[i + 1] * luma[1] + row[i + 2] * luma[2]
    private fun image(width: Int, row: FloatArray) = LinearSrgbImage.takeOwnership(width, 1, row)
    private fun values(image: LinearSrgbImage) = image.copyPixels()
    private fun checkMask(row: FloatArray) { require(row.all { it.isFinite() && it in 0f..1f }) }

    data class Result(val raster: OriginalRasterFile, val faceCutFactor: Double)

    fun render(input: OriginalRaster, externalEv: Double, strength: Double = .5,
        foundation: OriginalMask = OriginalMask.NONE, faces: OriginalMask = OriginalMask.NONE,
        stop: () -> Boolean = { false }, stage: (String, OriginalRasterFile) -> Unit = { _, _ -> }): Result {
        validateOriginalSize(input.width, input.height)
        require(externalEv.isFinite() && strength.isFinite() && strength in 0.0..1.0)
        val width = input.width; val height = input.height
        fun emit(name: String, raster: OriginalRasterFile): OriginalRasterFile {
            originalCheckpoint(stop); stage(name, raster); originalCheckpoint(stop); return raster
        }
        fun rowStep(previous: OriginalRasterFile, name: String, timing: OriginalStage, parallelRows: Boolean = false,
            transform: (Int, FloatArray) -> FloatArray): OriginalRasterFile {
            val next = emit(name, originalStage(trace, timing) {
                if (parallelRows) work.mapParallelRows(previous, stop, transform)
                else work.map(previous, stop, transform)
            })
            work.release(previous)
            return next
        }
        var base = emit("r7_profile", originalStage(trace, OriginalStage.R7_PROFILE) {
            work.mapParallelRows(input, stop) { _, row -> values(R7Base.profile(image(width, row))) }
        })
        val cell = .04 * max(width, height)
        val toneRow = FloatArray(width)
        val (tone, percentiles) = originalStage(trace, OriginalStage.R7_TONE_ANALYSIS) {
        val field = OriginalFields.reduce(base, max(4, round(width / cell).toInt()), max(4, round(height / cell).toInt()),
            1, stop, OriginalFilter.CUBIC) { rgb, out ->
            out.indices.forEach { p -> out[p] = log2(max(y(rgb, p * 3).toDouble(), 1e-5)).toFloat() }
        }
        val percentiles = OriginalQuantiles.evaluate(OriginalSamples { consumer ->
            repeat(height) { yy -> originalCheckpoint(stop); field.row(yy, toneRow); toneRow.forEach { consumer.accept(it) } }
        }, stop, .5, .92)
        field to percentiles
        }
        base = rowStep(base, "r7_tone", OriginalStage.R7_TONE_APPLY) { yy, row ->
            tone.row(yy, toneRow)
            FloatArray(row.size) { i ->
                val value = toneRow[i / 3].toDouble()
                val delta = -R7BaseV1.HIGHLIGHTS * max(value - percentiles[1], 0.0) -
                    R7BaseV1.SHADOWS * min(value - percentiles[0], 0.0)
                (row[i] * 2.0.pow(delta)).toFloat()
            }
        }
        val roiRow = FloatArray(width)
        base = rowStep(base, "r7_foundation", OriginalStage.R7_FOUNDATION) { yy, row ->
            foundation.row(yy, roiRow); checkMask(roiRow)
            values(R7Base.foundation(image(width, row), FloatPlane(width, 1, roiRow), externalEv))
        }
        base = rowStep(base, "r7_vibrance", OriginalStage.R7_VIBRANCE, parallelRows = true) { _, row -> values(R7Base.vibrance(image(width, row))) }
        base = rowStep(base, "r7_crisp", OriginalStage.R7_CRISP, parallelRows = true) { _, row -> values(R7Base.crisp(image(width, row))) }
        base = rowStep(base, "r7_skin", OriginalStage.R7_SKIN, parallelRows = true) { _, row -> values(R7Base.skin(image(width, row))) }

        val skin = originalStage(trace, OriginalStage.SKIN_FIELD) { OriginalFields.blur(base, StageClearGlowV1.SKIN_RADIUS, 1, stop) { rgb, out ->
            val frame = image(width, rgb); val lab = FloatArray(3)
            out.indices.forEach { p ->
                StageClearColor.toLab(frame, p * 3, lab)
                out[p] = StageClearColor.skinWeight(lab[0].toDouble(), hypot(lab[1].toDouble(), lab[2].toDouble()),
                    (Math.toDegrees(atan2(lab[2].toDouble(), lab[1].toDouble())) + 360) % 360).toFloat()
            }
        } }
        val skinRow = FloatArray(width)
        val glowed = if (strength == 0.0) base else originalStage(trace, OriginalStage.GLOW) { glow(base, skin, strength, stop) }.also {
            work.release(base); emit("glow", it)
        }
        fun layer(source: OriginalRasterFile): OriginalRasterFile {
            val nearWhite = originalStage(trace, OriginalStage.NEAR_WHITE) {
            var count = 0L
            source.rows(stop) { _, row -> repeat(width) { p -> if (y(row, p * 3) > StageClearMistV1.NEAR_WHITE.toFloat()) count++ } }
            count
            }
            val ratio = ((nearWhite.toDouble() / (width.toLong() * height) - StageClearMistV1.AUTO_START) /
                StageClearMistV1.AUTO_SPAN).coerceIn(0.0, 1.0)
            val amount = (1 - StageClearMistV1.AUTO_REDUCTION * ratio) * strength
            var current = if (strength == 0.0) source else originalStage(trace, OriginalStage.MIST) { mist(source, skin, amount, stop) }.also { emit("mist", it) }
            if (amount != 0.0) {
                val next = originalStage(trace, OriginalStage.ORTON) { orton(current, skin, amount, stop) }
                if (current !== source) work.release(current)
                current = emit("orton", next)
            }
            val aspect = width.toDouble() / height
            val xx = FloatArray(width) { ((it.toFloat() + .5f) / width * 2f - 1f) * min(aspect, 1.0).toFloat() }
            val faceRow = FloatArray(width)
            val result = originalStage(trace, OriginalStage.CHARACTER_CAP) { work.map(current, stop) { yy, row ->
                val py = ((yy.toFloat() + .5f) / height * 2f - 1f) / max(aspect, 1.0).toFloat()
                val vignette = FloatArray(row.size) { i ->
                    val radius = sqrt(xx[i / 3] * xx[i / 3] + py * py)
                    val t = ((radius - .45f) / .9f).coerceIn(0f, 1f)
                    row[i] * (1f - (StageClearCharacterV1.VIGNETTE * strength).toFloat() * t * t * (3f - 2f * t))
                }
                val vibrant = StageClearCharacter.vibrance(image(width, vignette), StageClearCharacterV1.VIBRANCE * strength)
                faces.row(yy, faceRow); checkMask(faceRow)
                values(StageClearProtections.skinChromaCap(vibrant, FaceMask(width, 1, faceRow)))
            } }
            if (current !== source) work.release(current)
            return emit("character_cap", result)
        }
        var layered = layer(glowed)
        val faceRow = FloatArray(width)
        val faceCount = originalStage(trace, OriginalStage.FACE_COUNT) {
            countOriginalMaskPixels(width, height, faces, stop)
        }
        val cut = if (faceCount < 50) 1.0 else {
            val top = originalStage(trace, OriginalStage.FACE_PERCENTILE) { OriginalQuantiles.evaluate(OriginalSamples { consumer ->
                layered.rows(stop) { yy, rgb ->
                    faces.row(yy, faceRow); checkMask(faceRow)
                    repeat(width) { p -> if (faceRow[p] > .5f)
                        consumer.accept(StageClearProtections.encode(y(rgb, p * 3).toDouble()).toFloat()) }
                }
            }, stop, .9)[0] }
            if (top <= StageClearV1.FACE_LIMIT) 1.0 else max(StageClearProtections.decode(StageClearV1.FACE_LIMIT) /
                StageClearProtections.decode(top), 2.0.pow(-StageClearV1.MAX_CUT_EV))
        }
        if (cut < 1.0) {
            work.release(layered)
            val scaled = originalStage(trace, OriginalStage.FACE_SCALE) {
                work.map(glowed, stop) { _, row -> FloatArray(row.size) { (row[it] * cut).toFloat() } }
            }
            layered = layer(scaled)
            work.release(scaled)
        }
        work.release(glowed)
        val final = emit("shoulder", originalStage(trace, OriginalStage.SHOULDER) {
            work.map(layered, stop) { _, row -> values(StageClearProtections.shoulder(image(width, row))) }
        })
        work.release(layered)
        return Result(final, cut)
    }

    private fun glow(base: OriginalRasterFile, skin: OriginalField, strength: Double, stop: () -> Boolean): OriginalRasterFile {
        val width = base.width
        fun project(rgb: FloatArray, out: FloatArray) {
            repeat(width) { p ->
                val yy = max(0f, y(rgb, p * 3))
                val excess = max(0f, (yy - StageClearGlowV1.THRESHOLD.toFloat()) / (1 - StageClearGlowV1.THRESHOLD).toFloat()).pow(1.5f)
                repeat(3) { c -> out[p * 3 + c] = rgb[p * 3 + c] * (excess / max(yy, 1e-6f)) }
            }
        }
        val kernels = StageClearGlowV1.BLOOM_KERNELS
        val fields = OriginalFields.blurMany(base, kernels.map { it.first }, 3, stop, ::project)
        val bloom = kernels.mapIndexed { index, (_, weight) -> fields[index] to weight }
        val halation = OriginalFields.blur(base, StageClearGlowV1.HALATION_RADIUS, 1, stop) { rgb, out ->
            out.indices.forEach { p ->
                val yy = max(0f, y(rgb, p * 3))
                out[p] = max(0f, (yy - StageClearGlowV1.THRESHOLD.toFloat()) / (1 - StageClearGlowV1.THRESHOLD).toFloat()).pow(1.5f)
            }
        }
        val skinRow = FloatArray(width); val haloRow = FloatArray(width)
        val bloomRows = bloom.map { FloatArray(width * 3) }; val tint = StageClearGlowV1.HALATION_TINT
        val light = work.map(base, stop) { yy, rgb ->
            skin.row(yy, skinRow); halation.row(yy, haloRow)
            bloom.forEachIndexed { i, pair -> pair.first.row(yy, bloomRows[i]) }
            FloatArray(rgb.size) { i ->
                var b = 0f; bloom.indices.forEach { k -> b += bloom[k].second * bloomRows[k][i] }
                rgb[i] + ((StageClearGlowV1.BLOOM * strength).toFloat() * b +
                    (StageClearGlowV1.HALATION * strength).toFloat() * haloRow[i / 3] * tint[i % 3]) *
                    (1f - StageClearGlowV1.SKIN_PROTECT.toFloat() * skinRow[i / 3].coerceIn(0f, 1f))
            }
        }
        val soft = OriginalFields.blur(light, StageClearGlowV1.DIFFUSION_RADIUS, 3, stop) { rgb, out -> rgb.copyInto(out) }
        val softRow = FloatArray(width * 3)
        val result = work.map(light, stop) { yy, rgb ->
            soft.row(yy, softRow); skin.row(yy, skinRow)
            FloatArray(rgb.size) { i ->
                val blurred = softRow[i].coerceIn(0f, 1f)
                rgb[i] + (StageClearGlowV1.DIFFUSION * strength).toFloat() *
                    (blurred - rgb[i].coerceIn(0f, 1f) * blurred) *
                    (1f - StageClearGlowV1.SKIN_PROTECT.toFloat() * skinRow[i / 3].coerceIn(0f, 1f))
            }
        }
        work.release(light)
        return result
    }

    private fun mist(source: OriginalRasterFile, skin: OriginalField, amount: Double, stop: () -> Boolean): OriginalRasterFile {
        val width = source.width
        val halos = StageClearMistV1.KERNELS.map { (radius, weight) ->
            OriginalFields.blur(source, radius, 3, stop) { rgb, out ->
                repeat(width) { p ->
                    val yy = max(y(rgb, p * 3), 0f)
                    val gain = ((yy - StageClearMistV1.SOURCE_START.toFloat()) / StageClearMistV1.SOURCE_SPAN.toFloat())
                        .coerceIn(0f, 1f).pow(StageClearMistV1.SOURCE_POWER.toFloat())
                    repeat(3) { c -> out[p * 3 + c] = rgb[p * 3 + c] * gain }
                }
            } to weight
        }
        val rows = halos.map { FloatArray(width * 3) }; val skinRow = FloatArray(width)
        return work.map(source, stop) { yy, rgb ->
            skin.row(yy, skinRow); halos.forEachIndexed { k, field -> field.first.row(yy, rows[k]) }
            FloatArray(rgb.size) { i ->
                var halo = 0f; halos.indices.forEach { k -> halo += halos[k].second * rows[k][i] }
                val candidate = rgb[i] + StageClearMistV1.MIST_AMOUNT.toFloat() * halo *
                    (1f - StageClearMistV1.SKIN_PROTECT.toFloat() * skinRow[i / 3].coerceIn(0f, 1f))
                rgb[i] + amount.toFloat() * (candidate - rgb[i])
            }
        }
    }

    private fun orton(source: OriginalRasterFile, skin: OriginalField, amount: Double, stop: () -> Boolean): OriginalRasterFile {
        require(parallelOrtonWorkers in setOf(1, 2, 4))
        val width = source.width
        val soft = originalStage(trace, OriginalStage.ORTON_FIELD) { OriginalFields.blur(source, StageClearOrtonV1.RADIUS, 3, stop) { rgb, out ->
            out.indices.forEach { i -> val inverse = 1f - StageClearColor.linearToUnit(rgb[i]); out[i] = 1f - inverse * inverse }
        } }
        val softRow = FloatArray(width * 3); val skinRow = FloatArray(width)
        val workerSoftRows = if (parallelOrtonWorkers > 1) ThreadLocal.withInitial { FloatArray(width * 3) } else null
        val workerSkinRows = if (parallelOrtonWorkers > 1) ThreadLocal.withInitial { FloatArray(width) } else null
        // Local JVM tests have an Android Debug stub; only device diagnostics emit CPU time.
        val pixelCpuNanos = if (trace != null && System.getProperty("java.vm.name") == "Dalvik") AtomicLong() else null
        // A running parallel row owns only private arrays and immutable fields. Check between
        // small pixel batches so cancellation/shutdownNow can stop siblings before cleanup.
        val workerStop = { stop() || Thread.currentThread().isInterrupted }
        val transform: (Int, FloatArray) -> FloatArray = { yy, rgb ->
            ortonRowProbe?.invoke(yy)
            if (parallelOrtonWorkers > 1) originalCheckpoint(workerStop)
            val activeSoftRow = workerSoftRows?.get() ?: softRow
            val activeSkinRow = workerSkinRows?.get() ?: skinRow
            soft.row(yy, activeSoftRow); skin.row(yy, activeSkinRow)
            if (parallelOrtonWorkers > 1) originalCheckpoint(workerStop)
            val pixelStart = if (pixelCpuNanos != null) Debug.threadCpuTimeNanos() else 0L
            val encoded = FloatArray(rgb.size)
            for (i in encoded.indices) {
                if (parallelOrtonWorkers > 1 && i % 192 == 0) originalCheckpoint(workerStop)
                encoded[i] = StageClearColor.linearToUnit(rgb[i])
            }
            val candidate = FloatArray(rgb.size)
            repeat(width) { p ->
                if (parallelOrtonWorkers > 1 && p % 64 == 0) originalCheckpoint(workerStop)
                val fade = StageClearOrton.highlightFade(y(encoded, p * 3))
                repeat(3) { c ->
                    val i = p * 3 + c; val inverse = 1f - encoded[i]; val bright = 1f - inverse * inverse
                    val display = encoded[i] + StageClearOrtonV1.AMOUNT.toFloat() * fade *
                        (bright * activeSoftRow[i].coerceIn(0f, 1f) - encoded[i])
                    val value = StageClearColor.unitToLinear(display)
                    candidate[i] = if (rgb[i] > 1f) max(rgb[i], value) else value
                }
            }
            val before = image(width, rgb); val after = image(width, candidate)
            val a = FloatArray(3); val b = FloatArray(3); val orton = FloatArray(rgb.size)
            repeat(width) { p ->
                if (parallelOrtonWorkers > 1 && p % 64 == 0) originalCheckpoint(workerStop)
                StageClearColor.toLab(before, p * 3, a); StageClearColor.toLab(after, p * 3, b)
                val keep = activeSkinRow[p].coerceIn(0f, 1f)
                val scale = (1f - keep) + keep * min(1f, hypot(a[1], a[2]) / max(hypot(b[1], b[2]), StageClearOrtonV1.CHROMA_FLOOR.toFloat()))
                b[1] *= scale; b[2] *= scale
                StageClearColor.toRgb(b, orton, p * 3)
            }
            val output = FloatArray(rgb.size)
            for (i in output.indices) {
                if (parallelOrtonWorkers > 1 && i % 192 == 0) originalCheckpoint(workerStop)
                output[i] = rgb[i] + amount.toFloat() * (orton[i] - rgb[i])
            }
            if (parallelOrtonWorkers > 1) originalCheckpoint(workerStop)
            if (pixelCpuNanos != null) pixelCpuNanos.addAndGet(Debug.threadCpuTimeNanos() - pixelStart)
            output
        }
        val result = originalStage(trace, OriginalStage.ORTON_COMPOSE) {
            if (parallelOrtonWorkers > 1) work.mapParallelRows(source, stop, parallelOrtonWorkers, transform)
            else work.map(source, stop, transform)
        }
        // Diagnostic counter: sum of per-worker CPU time, not elapsed wall time or an inclusive span.
        if (pixelCpuNanos != null) trace?.finished(OriginalStage.ORTON_PIXEL_THREAD_CPU, pixelCpuNanos.get(), true)
        return result
    }
}
