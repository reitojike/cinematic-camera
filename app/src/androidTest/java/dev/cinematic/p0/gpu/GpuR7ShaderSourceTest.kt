package dev.cinematic.p0.gpu

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.raw.R7BaseV1
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** GLSL 先行作成の asset 契約だけを検査する。GPU 実行・画質一致の証拠ではない。 */
@RunWith(AndroidJUnit4::class)
class GpuR7ShaderSourceTest {
    @Test fun packagedEntriesKeepTheShaderContract() {
        for (path in entries) {
            val source = read(path)
            assertTrue(path, source.startsWith("#version 320 es\n"))
            for (type in listOf("float", "int", "sampler2D", "isampler2D")) {
                assertTrue("$path: $type", source.contains("precision highp $type;"))
            }
            assertFalse(path, Regex("\\btexture\\s*\\(").containsMatchIn(source))
            for (match in Regex("uniform\\s+(\\w+)\\s+(\\w+)\\s*;").findAll(source)) {
                val (type, name) = match.destructured
                assertTrue("$path: $name", name.startsWith(if (type.endsWith("sampler2D")) "s_" else "u_"))
            }
            if (path.endsWith(".frag")) {
                assertTrue(path, source.contains("layout(location = 0) out vec4 o_color;"))
            }
        }
    }

    @Test fun pixelShadersReferenceTheKotlinR7Constants() {
        val referenced = entries.flatMap { path ->
            Regex("\\bSC_R7_([A-Z_]+)\\b").findAll(read(path)).map { it.groupValues[1] }.toList()
        }.toSet()
        // EV の2定数だけは Kotlin で u_gain を計算するときに使う。
        val cpuGainConstants = setOf("BRIGHTNESS_EV", "FOUNDATION_EV")
        val declared = R7BaseV1::class.java.declaredFields
            .filter { it.type == Double::class.javaPrimitiveType }.map { it.name }.toSet()
        assertEquals(declared - cpuGainConstants, referenced)
        for (path in entries) {
            assertFalse(path, Regex("#define\\s+SC_R7_").containsMatchIn(read(path)))
        }
    }

    @Test fun eachPixelShaderComputesHueOnlyOnce() {
        for (path in listOf("r7/profile.frag", "r7/finish.frag")) {
            assertEquals(path, 1, Regex("cs_hueDeg\\s*\\(").findAll(read(path)).count())
        }
    }

    @Test fun histogramStagesUseTheSameExactFloatKeyAndAllPixels() {
        val high = read("analysis/histogram_high.comp")
        val low = read("analysis/histogram_low.comp")
        for (source in listOf(high, low)) {
            assertTrue(source.contains("floatBitsToInt(texelFetch(s_input, pixel, 0).r)"))
            assertTrue(source.contains("uint(bits) ^ (bits < 0 ? 0xffffffffu : 0x80000000u)"))
            assertTrue(source.contains("if (any(greaterThanEqual(pixel, u_size))) return;"))
        }
        assertTrue(high.contains("uint counts[65536];"))
        assertTrue(high.contains("atomicAdd(counts[key >> 16u], 1u);"))
        assertTrue(low.contains("uniform uvec4 u_highBins;"))
        assertTrue(low.contains("uint(i) * 65536u + (key & 65535u)"))
    }

    private fun read(path: String): String = InstrumentationRegistry.getInstrumentation()
        .targetContext.assets.open("gpu/$path").bufferedReader().use { it.readText() }

    private val entries = listOf(
        "r7/profile.frag", "r7/log_luma.frag", "r7/tone_apply.frag", "r7/foundation.frag", "r7/finish.frag",
        "analysis/histogram_high.comp", "analysis/histogram_low.comp",
    )
}
