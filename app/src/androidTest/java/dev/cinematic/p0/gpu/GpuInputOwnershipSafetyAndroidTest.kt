package dev.cinematic.p0.gpu

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.view.Surface
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.preview.GpuPreviewRenderer
import dev.cinematic.p0.jpegrender.original.requireGpuInputSafetyFixture
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Ordinary-debug counterparts of diagnostic ownership assertions; no CPU image reference. */
class GpuInputOwnershipSafetyAndroidTest {
    @Before fun requireSyntheticExecution() = requireGpuInputSafetyFixture()

    @Test fun oldSurfaceDetachCannotClearNewAttachmentNotification() {
        val renderer = GpuPreviewRenderer(InstrumentationRegistry.getInstrumentation().targetContext.assets)
        val firstTexture = SurfaceTexture(false)
        val secondTexture = SurfaceTexture(false)
        val first = Surface(firstTexture)
        val second = Surface(secondTexture)
        try {
            assertFalse(renderer.surfaceAttached.value)
            renderer.attachSurface(first, 16, 16)
            assertTrue(renderer.surfaceAttached.value)
            renderer.attachSurface(second, 16, 16)
            renderer.detachSurface(first)
            assertTrue(renderer.hasSurface)
            assertTrue("Old detach must not publish false for the new lease", renderer.surfaceAttached.value)
            renderer.resizeSurface(first, 32, 32)
            assertTrue(renderer.surfaceAttached.value)
            renderer.detachSurface(second)
            assertFalse(renderer.hasSurface)
            assertFalse(renderer.surfaceAttached.value)
            renderer.attachSurface(first, 16, 16)
            renderer.close()
            assertFalse(renderer.surfaceAttached.value)
            renderer.detachSurface(first)
        } finally {
            renderer.close()
            assertTrue("Surface release follows the GL shutdown barrier", renderer.awaitClosed(10_000))
            first.release(); second.release()
            firstTexture.release(); secondTexture.release()
        }
    }

    @Test fun contextRejectsWrongThreadDrawAndCloseAndClosedUseWithoutLosingOwner() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        GlThread().use { thread ->
            val egl = thread.call { EglContexts(thread, assets) }
            val ledger = GpuMemoryLedger()
            try {
                val program = thread.call { egl.shaders().fragment("contract/copy.frag") }
                val graph = thread.call { egl.passes() }
                val texture = thread.call { GpuTexture.create(egl, ledger, 2, 2, GpuTexture.Format.RGBA32F) }
                try {
                    assertThrows(IllegalStateException::class.java) { egl.requireCurrent() }
                    assertThrows(IllegalStateException::class.java) { egl.close() }
                    assertThrows(IllegalStateException::class.java) { graph.run(FragmentPass(program, emptyList(), texture)) }
                    thread.call {
                        egl.requireCurrent()
                        assertSame(program, egl.shaders().fragment("contract/copy.frag"))
                        assertSame(graph, egl.passes())
                        assertEquals(1, ledger.textureCount)
                    }
                } finally { thread.call { texture.close() } }
                thread.call {
                    assertEquals(0L, ledger.allocatedBytes)
                    assertEquals(0, ledger.textureCount)
                    egl.close(); egl.close()
                    assertEquals(EGL14.EGL_NO_CONTEXT, EGL14.eglGetCurrentContext())
                    assertThrows(IllegalStateException::class.java) { egl.requireCurrent() }
                    assertThrows(IllegalStateException::class.java) { egl.shaders() }
                    assertThrows(IllegalStateException::class.java) { program.id }
                    EglContexts(thread, assets).use { next -> next.requireCurrent() }
                    assertEquals(EGL14.EGL_NO_CONTEXT, EGL14.eglGetCurrentContext())
                }
            } finally { thread.call { egl.close() } }
        }
    }

    @Test fun unboundContextIsRejectedThenClosedBeforeNextContext() {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        GlThread().use { thread -> thread.call {
            EglContexts(thread, assets).use { egl ->
                val display = EGL14.eglGetCurrentDisplay()
                assertTrue(EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT))
                assertThrows(IllegalStateException::class.java) { egl.requireCurrent() }
                assertEquals("Rejected use must not silently bind/recreate a context",
                    EGL14.EGL_NO_CONTEXT, EGL14.eglGetCurrentContext())
            }
            assertEquals(EGL14.EGL_NO_CONTEXT, EGL14.eglGetCurrentContext())
            EglContexts(thread, assets).use { next -> next.requireCurrent() }
            assertEquals(EGL14.EGL_NO_CONTEXT, EGL14.eglGetCurrentContext())
        } }
    }
}
