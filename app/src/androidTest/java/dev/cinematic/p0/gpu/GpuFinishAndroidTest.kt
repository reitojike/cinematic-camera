package dev.cinematic.p0.gpu

import android.opengl.GLES32
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.diag.GpuStageTap
import dev.cinematic.p0.gpu.diag.GpuStageTap.Point
import dev.cinematic.p0.gpu.look.MistGpuResult
import dev.cinematic.p0.gpu.look.StageClearFinishGpu
import dev.cinematic.p0.gpu.reference.GpuReferenceImages
import dev.cinematic.p0.gpu.reference.ImageDiff
import dev.cinematic.p0.gpu.reference.KotlinReference
import dev.cinematic.p0.raw.PhotoAdjustments
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** #282: CPU MIST/SKIN_WEIGHT inputs, followed only by GPU finishing stages. */
@RunWith(AndroidJUnit4::class)
class GpuFinishAndroidTest {
    @Before fun isolatedEmulatorOnly() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish"))
    }
    private fun gpu(block: (EglContexts, GpuMemoryLedger) -> Unit) = GlThread().use { thread ->
        thread.call { EglContexts(thread, InstrumentationRegistry.getInstrumentation().targetContext.assets).use { egl ->
            val caps = GlCapabilities.inspect(egl)
            Log.i("GpuFinish", "capabilities=$caps"); caps.requireSupported()
            val ledger = GpuMemoryLedger()
            block(egl, ledger)
            assertEquals(0L, ledger.allocatedBytes); assertEquals(0, ledger.textureCount)
        } }
    }
    private fun upload(egl: EglContexts, ledger: GpuMemoryLedger, w: Int, h: Int, data: FloatArray, rgb: Boolean): GpuTexture {
        val values = if (rgb) FloatArray(w * h * 4) { if (it % 4 == 3) 1f else data[it / 4 * 3 + it % 4] } else data
        val texture = GpuTexture.create(egl, ledger, w, h, if (rgb) GpuTexture.Format.RGBA32F else GpuTexture.Format.R32F)
        try { texture.upload(ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(values); flip() }); return texture }
        catch (failure: Throwable) { texture.close(); throw failure }
    }
    @Test fun finishStagesMatchKotlinAcrossAllImagesSizesAndStrengths() = gpu { egl, ledger ->
        var conditions = 0
        for (size in listOf(GpuReferenceImages.Size(256, 192), GpuReferenceImages.PREVIEW_SIZE)) {
            for (kind in GpuReferenceImages.Kind.entries) {
                val prepared = KotlinReference.prepare(GpuReferenceImages.create(kind, size), PhotoAdjustments())
                for (strength in listOf(0.0, .25, .5, 1.0)) {
                    val reference = KotlinReference.render(prepared, strength)
                    val w = size.width; val h = size.height
                    upload(egl, ledger, w, h, reference[Point.MIST].copyPixels(), true).use { mist ->
                        upload(egl, ledger, w, h, FloatArray(w * h) { reference.skinWeight[it] }, false).use { skin ->
                            upload(egl, ledger, w, h, FloatArray(w * h), false).use { faces ->
                                StageClearFinishGpu(egl, ledger).use { finish ->
                                    val seen = mutableListOf<Point>()
                                    var last = System.nanoTime()
                                    finish.render(MistGpuResult(mist, reference.lightAddingStrength, skin), strength, faces, tap = { point, texture ->
                                        val actual = GpuStageTap.readRgb(egl, texture)
                                        val elapsed = System.nanoTime() - last
                                        val expected = reference[point]
                                        repeat(expected.pixelCount * 3) { i ->
                                            val ref = expected[i].toDouble(); val value = actual[i].toDouble()
                                            val delta = abs(ref - value)
                                            assertTrue("$kind $size strength=$strength $point channel=$i ref=$ref actual=$value", value.isFinite() && delta <= 1e-4 * maxOf(1.0, abs(ref)))
                                        }
                                        if (point == Point.ORTON && reference.lightAddingStrength == 0.0) assertSame(mist, texture)
                                        seen += point
                                        Log.i("GpuFinish", "$kind $size strength=$strength $point synchronizedNs=$elapsed")
                                        last = System.nanoTime()
                                    }).use { shoulder ->
                                        finish.encode(shoulder).use { display ->
                                            val bytes = ByteBuffer.allocateDirect(w * h * 4)
                                            GpuFence(egl).use { it.await { false } }
                                            GLES32.glBindFramebuffer(GLES32.GL_FRAMEBUFFER, display.framebufferId)
                                            GLES32.glReadBuffer(GLES32.GL_COLOR_ATTACHMENT0)
                                            GLES32.glBindBuffer(GLES32.GL_PIXEL_PACK_BUFFER, 0)
                                            GLES32.glPixelStorei(GLES32.GL_PACK_ALIGNMENT, 1)
                                            GLES32.glPixelStorei(GLES32.GL_PACK_ROW_LENGTH, 0)
                                            GLES32.glPixelStorei(GLES32.GL_PACK_SKIP_PIXELS, 0)
                                            GLES32.glPixelStorei(GLES32.GL_PACK_SKIP_ROWS, 0)
                                            GLES32.glReadPixels(0, 0, w, h, GLES32.GL_RGBA, GLES32.GL_UNSIGNED_BYTE, bytes)
                                            egl.checkGl("finish display readback")
                                            val pixels = IntArray(w * h) { p ->
                                                assertEquals(255, bytes.get(p * 4 + 3).toInt() and 255)
                                                (255 shl 24) or ((bytes.get(p * 4).toInt() and 255) shl 16) or
                                                    ((bytes.get(p * 4 + 1).toInt() and 255) shl 8) or (bytes.get(p * 4 + 2).toInt() and 255)
                                            }
                                            ImageDiff.display(reference.display8, pixels, w, h, "$kind $size strength=$strength DISPLAY8").assertDisplayWithin(1, 1.0)
                                        }
                                    }
                                    assertEquals(listOf(Point.ORTON, Point.CHARACTER, Point.SKIN_CAP, Point.SHOULDER), seen)
                                }
                            }
                        }
                    }
                    conditions++
                }
            }
        }
        assertEquals(80, conditions)
        Log.i("GpuFinish", "PASS conditions=$conditions floatStages=320 displayStages=80")
    }
    @Test fun absentFaceMaskMatchesExplicitZeroAndNonzeroOrtonKeepsInputsOpen() = gpu { egl, ledger ->
        val w = 32; val h = 24
        upload(egl, ledger, w, h, FloatArray(w * h * 3) { (it % 17) / 16f }, true).use { input ->
            upload(egl, ledger, w, h, FloatArray(w * h), false).use { mask ->
                StageClearFinishGpu(egl, ledger).use { finish ->
                    val mist = MistGpuResult(input, .5, mask)
                    finish.render(mist, .5, mask).use { explicit ->
                        finish.render(mist, .5).use { absent ->
                            val a = GpuStageTap.readRgb(egl, explicit).copyPixels()
                            val b = GpuStageTap.readRgb(egl, absent).copyPixels()
                            assertArrayEquals(a, b, 0f)
                        }
                    }
                    input.requireContext(egl); mask.requireContext(egl)
                    for (point in listOf(Point.ORTON, Point.CHARACTER, Point.SKIN_CAP, Point.SHOULDER)) {
                        val baseline = ledger.allocatedBytes
                        assertThrows(IllegalStateException::class.java) {
                            finish.render(mist, .5, tap = { stage, _ -> if (stage == point) error("tap failure") })
                        }
                        assertEquals(baseline, ledger.allocatedBytes)
                    }
                }
            }
        }
    }
    @Test fun rejectsFaceCutAndReleasesIntermediatesWhenTapThrows() = gpu { egl, ledger ->
        upload(egl, ledger, 2, 2, FloatArray(12) { .25f }, true).use { input ->
            upload(egl, ledger, 2, 2, FloatArray(4), false).use { mask ->
                StageClearFinishGpu(egl, ledger).use { finish ->
                    val mist = MistGpuResult(input, 0.0, mask)
                    assertThrows(IllegalArgumentException::class.java) { finish.render(mist, 1.0, mask, .9) }
                    val baseline = ledger.allocatedBytes
                    for (point in listOf(Point.ORTON, Point.CHARACTER, Point.SKIN_CAP, Point.SHOULDER)) {
                        assertThrows(IllegalStateException::class.java) {
                            finish.render(mist, 1.0, mask, tap = { stage, _ -> if (stage == point) error("tap failure") })
                        }
                        assertEquals(baseline, ledger.allocatedBytes)
                    }
                }
            }
        }
    }
}
