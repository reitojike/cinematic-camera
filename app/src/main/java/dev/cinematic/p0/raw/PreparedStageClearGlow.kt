package dev.cinematic.p0.raw

import kotlin.math.max
import kotlin.math.pow

/** Only the strength-independent Glow fields are cached. Existing nonlinear stages remain intact. */
internal class PreparedStageClearGlow private constructor(
    private val base: LinearSrgbImage,
    private val skin: Field,
    private val halo: Field,
    private val bloom: List<List<Field>>,
) {
    // Counts retained field payload; excludes base (already session-owned), objects and ephemeral row maps.
    val retainedFieldBytes = skin.bytes + halo.bytes + bloom.sumOf { channels -> channels.sumOf { it.bytes } }

    fun render(faces: FaceMask, strength: Double, checkpoint: () -> Unit): StageClearProtections.Result =
        render(faces, strength, null, checkpoint)

    fun render(
        faces: FaceMask,
        strength: Double,
        decisions: StageClearDecisions?,
        checkpoint: () -> Unit,
    ): StageClearProtections.Result = render(faces, strength, decisions, null, checkpoint)

    internal fun render(
        faces: FaceMask, strength: Double, decisions: StageClearDecisions?,
        timing: RawPreviewTiming?, checkpoint: () -> Unit,
    ): StageClearProtections.Result {
        require(strength.isFinite() && strength in 0.0..1.0)
        faces.requireAligned(base)
        checkpoint()
        timing?.enter(RawPreviewTiming.Stage.SKIN_EXPAND)
        val skinValues = FloatArray(base.pixelCount)
        val skinRows = skin.rows(base.width, base.height)
        val row = FloatArray(base.width)
        repeat(base.height) { y ->
            checkpoint(); skinRows.read(y, row)
            repeat(base.width) { x -> skinValues[y * base.width + x] = row[x].coerceIn(0f, 1f) }
        }
        val originalSkin = FloatPlane.takeOwnership(base.width, base.height, skinValues)
        timing?.enter(RawPreviewTiming.Stage.GLOW)
        val glowed = if (strength == 0.0) base else glow(strength, originalSkin, checkpoint)
        checkpoint()
        return StageClearLook.renderFromGlowed(glowed, originalSkin, faces, strength, decisions, timing, checkpoint)
    }

    private fun glow(strength: Double, skin: FloatPlane, checkpoint: () -> Unit): LinearSrgbImage {
        val w = base.width; val h = base.height
        val haloRows = halo.rows(w, h)
        val bloomRows = bloom.map { channels -> channels.map { it.rows(w, h) } }
        val haloRow = FloatArray(w)
        val scratch = FloatArray(w)
        val summed = FloatArray(w * 3)
        val values = FloatArray(base.pixelCount * 3)
        val kernels = StageClearGlowV1.BLOOM_KERNELS
        val tint = StageClearGlowV1.HALATION_TINT
        val bloomAmount = (StageClearGlowV1.BLOOM * strength).toFloat()
        val haloAmount = (StageClearGlowV1.HALATION * strength).toFloat()
        repeat(h) { y ->
            checkpoint(); summed.fill(0f); haloRows.read(y, haloRow)
            bloomRows.forEachIndexed { k, channels ->
                repeat(3) { c ->
                    channels[c].read(y, scratch)
                    repeat(w) { x -> summed[x * 3 + c] += kernels[k].second * scratch[x] }
                }
            }
            repeat(w) { x ->
                val p = y * w + x
                val protect = 1f - StageClearGlowV1.SKIN_PROTECT.toFloat() * skin[p]
                repeat(3) { c ->
                    values[p * 3 + c] = base[p * 3 + c] +
                        (bloomAmount * summed[x * 3 + c] + haloAmount * (haloRow[x] * tint[c])) * protect
                }
            }
        }
        // Existing strength-dependent diffusion, including snapshot ownership and clipping order.
        val glowed = LinearSrgbImage(w, h, values)
        checkpoint()
        val soft = StageClearBlur.rgb(glowed, StageClearGlowV1.DIFFUSION_RADIUS)
        val amount = (StageClearGlowV1.DIFFUSION * strength).toFloat()
        repeat(h) { y ->
            checkpoint()
            repeat(w * 3) { x ->
                val i = y * w * 3 + x
                val blurred = soft[i].coerceIn(0f, 1f)
                val clipped = values[i].coerceIn(0f, 1f)
                values[i] += amount * (blurred - clipped * blurred) *
                    (1f - StageClearGlowV1.SKIN_PROTECT.toFloat() * skin[i / 3])
            }
        }
        return LinearSrgbImage.takeOwnership(w, h, values)
    }

    private class Field(private val reduced: FloatPlane) {
        val bytes = reduced.width.toLong() * reduced.height * 4
        fun rows(width: Int, height: Int) = Rows(width, height)
        inner class Rows(private val width: Int, private val height: Int) {
            private val left = IntArray(width)
            private val right = IntArray(width)
            private val mix = FloatArray(width)
            init {
                repeat(width) { x ->
                    val p = ((x + .5) * reduced.width / width - .5).coerceIn(0.0, (reduced.width - 1).toDouble())
                    left[x] = p.toInt(); right[x] = minOf(left[x] + 1, reduced.width - 1); mix[x] = (p - left[x]).toFloat()
                }
            }
            fun read(y: Int, out: FloatArray) {
                val p = ((y + .5) * reduced.height / height - .5).coerceIn(0.0, (reduced.height - 1).toDouble())
                val top = p.toInt(); val bottom = minOf(top + 1, reduced.height - 1); val dy = (p - top).toFloat()
                repeat(width) { x ->
                    val dx = mix[x]
                    val a = reduced[top * reduced.width + left[x]] * (1f - dx) + reduced[top * reduced.width + right[x]] * dx
                    val b = reduced[bottom * reduced.width + left[x]] * (1f - dx) + reduced[bottom * reduced.width + right[x]] * dx
                    out[x] = a * (1f - dy) + b * dy
                }
            }
        }
    }

    companion object {
        fun prepare(base: LinearSrgbImage, checkpoint: () -> Unit): PreparedStageClearGlow {
            // Preview experiment, not admission for full-resolution export.
            require(base.width in 2..2048 && base.height in 2..2048)
            fun reduce(plane: FloatPlane, fraction: Double): Field {
                checkpoint()
                val (w, h) = StageClearBlur.reducedSize(plane.width, plane.height, fraction)
                return Field(StageClearBlur.resize(plane, w, h, StageClearBlur.Filter.BOX))
            }
            val skin = reduce(StageClearColor.skinWeight(base), StageClearGlowV1.SKIN_RADIUS)
            val luma = StageClearV1.LUMA
            val threshold = StageClearGlowV1.THRESHOLD.toFloat()
            val span = (1 - StageClearGlowV1.THRESHOLD).toFloat()
            val excess = FloatArray(base.pixelCount)
            repeat(base.height) { y ->
                checkpoint()
                repeat(base.width) { x ->
                    val p = y * base.width + x; val i = p * 3
                    val light = max(0f, base[i] * luma[0] + base[i + 1] * luma[1] + base[i + 2] * luma[2])
                    excess[p] = max(0f, (light - threshold) / span).pow(1.5f)
                }
            }
            val halo = reduce(FloatPlane.takeOwnership(base.width, base.height, excess), StageClearGlowV1.HALATION_RADIUS)
            val byChannel = (0..2).map { channel ->
                val source = FloatArray(base.pixelCount)
                repeat(base.height) { y ->
                    checkpoint()
                    repeat(base.width) { x ->
                        val p = y * base.width + x; val i = p * 3
                        val light = max(0f, base[i] * luma[0] + base[i + 1] * luma[1] + base[i + 2] * luma[2])
                        source[p] = base[i + channel] * (excess[p] / max(light, 1e-6f))
                    }
                }
                val plane = FloatPlane.takeOwnership(base.width, base.height, source)
                StageClearGlowV1.BLOOM_KERNELS.map { reduce(plane, it.first) }
            }
            checkpoint()
            return PreparedStageClearGlow(base, skin, halo, StageClearGlowV1.BLOOM_KERNELS.indices.map { k ->
                (0..2).map { c -> byChannel[c][k] }
            })
        }
    }
}
