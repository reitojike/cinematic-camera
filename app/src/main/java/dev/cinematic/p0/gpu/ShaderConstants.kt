package dev.cinematic.p0.gpu

import dev.cinematic.p0.raw.StageClearV1
import dev.cinematic.p0.raw.StageClearGlowV1
import dev.cinematic.p0.raw.StageClearMistV1
import dev.cinematic.p0.raw.StageClearOrtonV1
import dev.cinematic.p0.raw.StageClearCharacterV1
import dev.cinematic.p0.raw.R7BaseV1

/** V1定数の明示的な対応表。実行時リフレクションは使わない。 */
internal object ShaderConstants {
    val values: Map<String, Any> get() = linkedMapOf(
        "SC_STAGECLEAR_VERSION" to StageClearV1.VERSION,
        "SC_STAGECLEAR_DEFAULT_STRENGTH" to StageClearV1.DEFAULT_STRENGTH,
        "SC_STAGECLEAR_SKIN_KNEE" to StageClearV1.SKIN_KNEE,
        "SC_STAGECLEAR_SKIN_SLOPE" to StageClearV1.SKIN_SLOPE,
        "SC_STAGECLEAR_SKIN_HUE_LIFT" to StageClearV1.SKIN_HUE_LIFT,
        "SC_STAGECLEAR_SKIN_VIVID" to StageClearV1.SKIN_VIVID,
        "SC_STAGECLEAR_RED_TARGET" to StageClearV1.RED_TARGET,
        "SC_STAGECLEAR_SHOULDER" to StageClearV1.SHOULDER,
        "SC_STAGECLEAR_FACE_LIMIT" to StageClearV1.FACE_LIMIT,
        "SC_STAGECLEAR_MAX_CUT_EV" to StageClearV1.MAX_CUT_EV,
        "SC_STAGECLEAR_LUMA" to StageClearV1.LUMA,
        "SC_STAGECLEAR_RGB_TO_LMS" to StageClearV1.RGB_TO_LMS,
        "SC_STAGECLEAR_LMS_TO_LAB" to StageClearV1.LMS_TO_LAB,
        "SC_STAGECLEAR_LAB_TO_LMS" to StageClearV1.LAB_TO_LMS,
        "SC_STAGECLEAR_LMS_TO_RGB" to StageClearV1.LMS_TO_RGB,
        "SC_GLOW_THRESHOLD" to StageClearGlowV1.THRESHOLD,
        "SC_GLOW_BLOOM" to StageClearGlowV1.BLOOM,
        "SC_GLOW_HALATION" to StageClearGlowV1.HALATION,
        "SC_GLOW_DIFFUSION" to StageClearGlowV1.DIFFUSION,
        "SC_GLOW_HALATION_RADIUS" to StageClearGlowV1.HALATION_RADIUS,
        "SC_GLOW_SKIN_RADIUS" to StageClearGlowV1.SKIN_RADIUS,
        "SC_GLOW_SKIN_PROTECT" to StageClearGlowV1.SKIN_PROTECT,
        "SC_GLOW_DIFFUSION_RADIUS" to StageClearGlowV1.DIFFUSION_RADIUS,
        "SC_GLOW_BLOOM_KERNELS" to StageClearGlowV1.BLOOM_KERNELS,
        "SC_GLOW_HALATION_TINT" to StageClearGlowV1.HALATION_TINT,
        "SC_MIST_SOURCE_START" to StageClearMistV1.SOURCE_START,
        "SC_MIST_SOURCE_SPAN" to StageClearMistV1.SOURCE_SPAN,
        "SC_MIST_SOURCE_POWER" to StageClearMistV1.SOURCE_POWER,
        "SC_MIST_MIST_AMOUNT" to StageClearMistV1.MIST_AMOUNT,
        "SC_MIST_SKIN_PROTECT" to StageClearMistV1.SKIN_PROTECT,
        "SC_MIST_NEAR_WHITE" to StageClearMistV1.NEAR_WHITE,
        "SC_MIST_AUTO_START" to StageClearMistV1.AUTO_START,
        "SC_MIST_AUTO_SPAN" to StageClearMistV1.AUTO_SPAN,
        "SC_MIST_AUTO_REDUCTION" to StageClearMistV1.AUTO_REDUCTION,
        "SC_MIST_KERNELS" to StageClearMistV1.KERNELS,
        "SC_ORTON_RADIUS" to StageClearOrtonV1.RADIUS,
        "SC_ORTON_AMOUNT" to StageClearOrtonV1.AMOUNT,
        "SC_ORTON_HIGHLIGHT_START" to StageClearOrtonV1.HIGHLIGHT_START,
        "SC_ORTON_HIGHLIGHT_SPAN" to StageClearOrtonV1.HIGHLIGHT_SPAN,
        "SC_ORTON_CHROMA_FLOOR" to StageClearOrtonV1.CHROMA_FLOOR,
        "SC_CHARACTER_VIGNETTE" to StageClearCharacterV1.VIGNETTE,
        "SC_CHARACTER_VIBRANCE" to StageClearCharacterV1.VIBRANCE,
        "SC_R7_FOUNDATION_EV" to R7BaseV1.FOUNDATION_EV,
        "SC_R7_CONTRAST" to R7BaseV1.CONTRAST,
        "SC_R7_SATURATION" to R7BaseV1.SATURATION,
        "SC_R7_SKIN_SATURATION" to R7BaseV1.SKIN_SATURATION,
        "SC_R7_YELLOW_RED" to R7BaseV1.YELLOW_RED,
        "SC_R7_YELLOW_GREEN" to R7BaseV1.YELLOW_GREEN,
        "SC_R7_YELLOW_BLUE" to R7BaseV1.YELLOW_BLUE,
        "SC_R7_PROFILE_SHIFT" to R7BaseV1.PROFILE_SHIFT,
        "SC_R7_HIGHLIGHTS" to R7BaseV1.HIGHLIGHTS,
        "SC_R7_SHADOWS" to R7BaseV1.SHADOWS,
        "SC_R7_VIBRANCE" to R7BaseV1.VIBRANCE,
        "SC_R7_BRIGHTNESS_EV" to R7BaseV1.BRIGHTNESS_EV,
        "SC_R7_BLACK" to R7BaseV1.BLACK,
        "SC_R7_CRISP_CONTRAST" to R7BaseV1.CRISP_CONTRAST,
        "SC_R7_CRISP_CHROMA" to R7BaseV1.CRISP_CHROMA,
    )

    fun defines(): String = buildString {
        fun scalar(name: String, value: Any) { append("#define ").append(name).append(' ').append(literal(value)).append('\n') }
        for ((name, value) in values) when (value) {
            is FloatArray -> {
                scalar("${name}_COUNT", value.size)
                value.forEachIndexed { index, item -> scalar("${name}_$index", item) }
            }
            is Array<*> -> {
                scalar("${name}_COUNT", value.size)
                value.forEachIndexed { index, item ->
                    val pair = item as Pair<*, *>
                    scalar("${name}_${index}_RADIUS", checkNotNull(pair.first))
                    scalar("${name}_${index}_WEIGHT", checkNotNull(pair.second))
                }
            }
            else -> scalar(name, value)
        }
    }

    private fun literal(value: Any): String = when (value) {
        is Double -> { require(value.isFinite()); value.toString() }
        is Float -> { require(value.isFinite()); value.toString() }
        is Int -> value.toString()
        // VERSION is a metadata token sequence, not a GLSL numeric value. No quoted strings in GLSL.
        is String -> value.also { require(it.matches(Regex("[A-Za-z0-9_.-]+"))) }
        else -> error("Unsupported shader constant ${value::class.java.simpleName}")
    }
}
