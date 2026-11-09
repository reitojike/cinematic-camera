package dev.cinematic.p0.gpu

import dev.cinematic.p0.raw.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Modifier

class ShaderConstantsTest {
    @Test fun everyV1ConstantHasAnExplicitEntryAndExactValue() {
        val groups = listOf(StageClearV1 to "STAGECLEAR", StageClearGlowV1 to "GLOW", StageClearMistV1 to "MIST",
            StageClearOrtonV1 to "ORTON", StageClearCharacterV1 to "CHARACTER", R7BaseV1 to "R7")
        val actual = ShaderConstants.values
        var count = 0
        for ((obj, prefix) in groups) {
            val constants = obj.javaClass.declaredFields.filter { Modifier.isStatic(it.modifiers) && Modifier.isFinal(it.modifiers) && it.name != "INSTANCE" && !it.name.startsWith("$") }
            val arrays = obj.javaClass.declaredMethods.filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType.isArray }
            for (field in constants) {
                field.isAccessible = true
                val key = "SC_${prefix}_${field.name}"
                assertEquals(key, field.get(null), actual[key]); count++
            }
            for (getter in arrays) {
                val key = "SC_${prefix}_${getter.name.removePrefix("get")}"
                val expected = getter.invoke(obj)
                when (expected) {
                    is FloatArray -> assertArrayEquals(key, expected, actual[key] as FloatArray, 0f)
                    is Array<*> -> assertArrayEquals(key, expected, actual[key] as Array<*>)
                    else -> fail(key)
                }
                count++
            }
        }
        assertEquals(count, actual.size)
    }

    @Test fun definesPreserveDoubleDigitsAndExpandEveryArrayElement() {
        val defines = ShaderConstants.defines().lineSequence().filter { it.isNotBlank() }.associate {
            val parts = it.split(' ', limit = 3); parts[1] to parts[2]
        }
        var expectedCount = 0
        for ((key, value) in ShaderConstants.values) when (value) {
            is FloatArray -> {
                assertEquals(value.size.toString(), defines["${key}_COUNT"]); expectedCount++
                value.forEachIndexed { i, item -> assertEquals(item.toString(), defines["${key}_$i"]); expectedCount++ }
            }
            is Array<*> -> {
                assertEquals(value.size.toString(), defines["${key}_COUNT"]); expectedCount++
                value.forEachIndexed { i, item ->
                    val pair = item as Pair<*, *>
                    assertEquals(pair.first.toString(), defines["${key}_${i}_RADIUS"])
                    assertEquals(pair.second.toString(), defines["${key}_${i}_WEIGHT"]); expectedCount += 2
                }
            }
            else -> { assertEquals(value.toString(), defines[key]); expectedCount++ }
        }
        assertEquals(expectedCount, defines.size)
        assertEquals(StageClearGlowV1.THRESHOLD.toString(), defines["SC_GLOW_THRESHOLD"])
    }

    @Test fun includeExpansionInjectionCyclesAndPaths() {
        val files = mapOf("entry.frag" to "#version 320 es\n#include \"common/a.glsl\"\nvoid main() {}",
            "common/a.glsl" to "#include \"common/b.glsl\"\nfloat a;", "common/b.glsl" to "float b;")
        val text = ShaderSource(files::getValue) { "#define TEST 1\n" }.expand("entry.frag")
        assertTrue(text.startsWith("#version 320 es\n#define TEST 1\nfloat b;\nfloat a;"))
        assertThrows(IllegalStateException::class.java) {
            ShaderSource({ "#version 320 es\n#include \"same.frag\"" }).expand("same.frag")
        }
        assertThrows(IllegalArgumentException::class.java) { ShaderSource(files::getValue).expand("../entry.frag") }
        assertThrows(IllegalArgumentException::class.java) { ShaderSource({ "#version 320 es\n#version 320 es" }).expand("x") }
    }

    @Test fun memoryBudgetCannotUnderflowOrOvercommit() {
        val ledger = GpuMemoryLedger(32)
        ledger.reserve(16); ledger.reserve(16)
        assertThrows(GpuMemoryBudgetException::class.java) { ledger.reserve(1) }
        assertEquals(32L, ledger.allocatedBytes); assertEquals(2, ledger.textureCount)
        ledger.release(16); ledger.release(16)
        assertEquals(0L, ledger.allocatedBytes); assertEquals(0, ledger.textureCount)
        assertThrows(IllegalStateException::class.java) { ledger.release(16) }
    }
}
