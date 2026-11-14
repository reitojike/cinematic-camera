package dev.cinematic.p0.raw

import dev.cinematic.p0.gpu.constants.R7BaseV1
import java.util.concurrent.CancellationException
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.sqrt

/** Diagnostic controls only; no RAW decode, WB correction, detector, face AE, rendering or I/O. */
internal object R7PreviewInputs {
    const val MAX_LONG_EDGE = 1024
    const val MAX_ELLIPSES = 6

    /** Caller-declared origin of the plain input; neither option claims Pixel profile equivalence. */
    enum class Source { LIBRAW_PRELIMINARY_AS_SHOT, SYNTHETIC_AS_SHOT }
    enum class WhiteBalanceState { AS_SHOT_BEFORE_W1 }
    enum class ExposureMethod { PC_CENTRAL_P97_DIAGNOSTIC }
    enum class FoundationRoiOrigin { EXPLICIT_NORMALIZED_ELLIPSES }
    enum class FaceDetectionStatus { NOT_RUN }

    class AsShotInput(val image: LinearSrgbImage, val source: Source) {
        init {
            require(max(image.width, image.height) <= MAX_LONG_EDGE) { "Preview long edge must not exceed 1024" }
            require(3 * image.width / 4 > image.width / 4 && 3 * image.height / 4 > image.height / 4) {
                "Preview must have a nonempty central metering region"
            }
        }
    }

    /** Normalized pixel-centre ellipse; extending beyond the frame is allowed. Not a detected face. */
    data class FoundationEllipse(val centerX: Float, val centerY: Float, val radiusX: Float, val radiusY: Float) {
        init {
            require(centerX.isFinite() && centerY.isFinite() && centerX in 0f..1f && centerY in 0f..1f) {
                "Ellipse centre must be normalized within 0..1"
            }
            require(radiusX.isFinite() && radiusY.isFinite() && radiusX > 0f && radiusX <= 1f &&
                radiusY > 0f && radiusY <= 1f) { "Ellipse radii must be finite and within (0,1]" }
        }
    }

    data class DiagnosticExposure(
        val externalBaseEv: Double,
        val centralP97: Float,
        val centralSampleCount: Int,
        val source: Source,
        val whiteBalance: WhiteBalanceState = WhiteBalanceState.AS_SHOT_BEFORE_W1,
        val method: ExposureMethod = ExposureMethod.PC_CENTRAL_P97_DIAGNOSTIC,
    )

    data class Prepared(
        val exposure: DiagnosticExposure,
        val foundationSkinRoi: FloatPlane,
        val explicitEllipseCount: Int,
        val foundationRoiOrigin: FoundationRoiOrigin = FoundationRoiOrigin.EXPLICIT_NORMALIZED_ELLIPSES,
        val faceDetection: FaceDetectionStatus = FaceDetectionStatus.NOT_RUN,
    )

    /** Empty ellipses must be explicit. NOT_RUN never means that a detector found no faces. */
    fun prepare(input: AsShotInput, ellipses: List<FoundationEllipse>, shouldStop: () -> Boolean = { false }): Prepared =
        prepare(input, ellipses, null, shouldStop)

    internal fun prepare(input: AsShotInput, ellipses: List<FoundationEllipse>, timing: R7StageTiming?,
        shouldStop: () -> Boolean): Prepared {
        fun checkpoint() {
            if (shouldStop()) throw CancellationException("R7 preview inputs stopped")
        }
        checkpoint()
        require(ellipses.size <= MAX_ELLIPSES) { "At most six foundation ellipses are supported" }
        val regions = ellipses.toList()
        val image = input.image
        timing?.enter(R7StageTiming.Stage.METER_LUMA)
        val x0 = image.width / 4; val x1 = 3 * image.width / 4
        val y0 = image.height / 4; val y1 = 3 * image.height / 4
        val luminance = FloatArray((x1 - x0) * (y1 - y0))
        var next = 0
        for (y in y0 until y1) {
            checkpoint()
            for (x in x0 until x1) {
                val i = (y * image.width + x) * 3
                val value = image[i] * .2126f + image[i + 1] * .7152f + image[i + 2] * .0722f
                require(value.isFinite()) { "Central luminance must be finite" }
                luminance[next++] = value
            }
        }
        checkpoint()
        timing?.enter(R7StageTiming.Stage.METER_QUANTILE)
        val rank = (luminance.size - 1) * .97
        val lo = floor(rank).toInt(); val hi = ceil(rank).toInt()
        val lower = PreviewFloatSelection.select(luminance, lo, ::checkpoint)
        val upper = if (hi == lo) lower else PreviewFloatSelection.select(luminance, hi, ::checkpoint)
        val fraction = (rank - lo).toFloat()
        val delta = upper - lower
        // NumPy's float32 linear quantile uses the nearer endpoint for interpolation.
        val p97 = if (fraction >= .5f) upper - delta * (1f - fraction)
            else lower + delta * fraction
        require(p97.isFinite()) { "Central percentile must be finite" }
        val ev = (-.13 - .83 * log2(max(p97.toDouble(), 1e-5))).coerceIn(-3.0, 6.0) - R7BaseV1.BRIGHTNESS_EV
        timing?.enter(R7StageTiming.Stage.ROI)
        val weights = FloatArray(image.pixelCount)
        for (y in 0 until image.height) {
            checkpoint()
            val cy = (y.toFloat() + .5f) / image.height
            for (x in 0 until image.width) {
                val cx = (x.toFloat() + .5f) / image.width
                var weight = 0f
                for (ellipse in regions) {
                    val dx = (cx - ellipse.centerX) / ellipse.radiusX
                    val dy = (cy - ellipse.centerY) / ellipse.radiusY
                    val radius = sqrt(dx * dx + dy * dy)
                    val t = ((1f - radius) / .45f).coerceIn(0f, 1f)
                    weight = max(weight, t * t * (3f - 2f * t))
                }
                weights[y * image.width + x] = weight
            }
        }
        val result = Prepared(DiagnosticExposure(ev, p97, luminance.size, input.source),
            FloatPlane(image.width, image.height, weights), regions.size)
        checkpoint()
        return result
    }
}
