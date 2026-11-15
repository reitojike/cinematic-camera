package dev.cinematic.p0.photoroll

import android.opengl.GLES32
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.gpu.EglContexts
import dev.cinematic.p0.gpu.GlCapabilities
import dev.cinematic.p0.gpu.GlThread
import dev.cinematic.p0.jpegrender.original.requireGpuInputSafetyFixture
import java.io.File
import org.json.JSONObject
import org.junit.Test

/** Backend observation in a separate instrumentation process, outside allocation samples. */
class InitialPreviewMesaBackendAndroidTest {
    @Test fun recordActualMesaRenderer() {
        requireGpuInputSafetyFixture()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val values = GlThread().use { thread -> thread.call {
            EglContexts(thread, instrumentation.targetContext.assets).use { egl ->
                GlCapabilities.inspect(egl).requireSupported()
                JSONObject().put("vendor", GLES32.glGetString(GLES32.GL_VENDOR))
                    .put("renderer", GLES32.glGetString(GLES32.GL_RENDERER))
                    .put("version", GLES32.glGetString(GLES32.GL_VERSION))
            }
        } }
        val identity = values.toString().lowercase()
        val renderer = values.getString("renderer").lowercase()
        check("swiftshader" !in identity && ("lavapipe" in renderer || "llvmpipe" in renderer)) {
            "Mesa renderer identity not established; no backend fallback accepted"
        }
        val base = File(checkNotNull(InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")))
        val output = File(base, "initial-preview-allocation").apply { check(isDirectory || mkdirs()) }
        File(output, "backend.json").writeText(values.toString())
    }
}
