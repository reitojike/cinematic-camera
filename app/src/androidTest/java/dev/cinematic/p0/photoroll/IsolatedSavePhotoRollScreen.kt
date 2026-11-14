package dev.cinematic.p0.photoroll

import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.CaptureTheme
import dev.cinematic.p0.gpu.DevelopEngine
import dev.cinematic.p0.gpu.preview.GpuInputPublication
import dev.cinematic.p0.rawcapture.DevelopExecution
import dev.cinematic.p0.rawcapture.AndroidManualOriginalTrialSaves
import dev.cinematic.p0.jpegrender.original.preserveSafetyCleanup
import java.io.File
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Synthetic test UI with all persistent app accesses redirected to its supplied isolated context. */
internal class IsolatedSavePhotoRollScreen(private val app: Context, private val ui: SyntheticFixtureRule) {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
        private val publications = MutableStateFlow(GpuInputPublication())
        private val observer = PhotoRollResourceObserver(app, DevelopExecution::updateConditions,
            onPublication = { value -> publications.update { GpuInputPublication(it.sequence + 1, value) } })
        private val renderer = ManualTrialPhotoRollRenderer(app, observer::snapshot, developEngine = DevelopEngine.GPU)
        private val repository = PhotoRollRepository(app.contentResolver, app.packageName)
        private var camera: PhotoRollCameraOwners.Owner? = null

        fun install() {
            instrumentation.runOnMainSync {
                camera = AndroidPhotoRollCameraObservations.register { PhotoRollCameraSnapshot(false, false, false, false) }
            }
            ui.content = {
                val host = requireNotNull(LocalActivity.current) as PhotoRollActivity
                val context = remember(host) { object : ContextWrapper(host) {
                    override fun getApplicationContext(): Context = app
                    override fun getNoBackupFilesDir(): File = app.noBackupFilesDir
                    override fun getCacheDir(): File = app.cacheDir
                    override fun getSharedPreferences(name: String, mode: Int) = app.getSharedPreferences(name, mode)
                    override fun getContentResolver() = app.contentResolver
                } }
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                val state by lifecycle.currentStateFlow.collectAsState()
                DisposableEffect(lifecycle) {
                    val listener = LifecycleEventObserver { _, event -> when (event) {
                        Lifecycle.Event.ON_START -> observer.start()
                        Lifecycle.Event.ON_RESUME -> observer.foreground(true)
                        Lifecycle.Event.ON_PAUSE -> observer.foreground(false)
                        Lifecycle.Event.ON_STOP -> observer.stop()
                        else -> Unit
                    } }
                    lifecycle.addObserver(listener)
                    onDispose { lifecycle.removeObserver(listener); observer.stop() }
                }
                CompositionLocalProvider(LocalContext provides context) {
                    CaptureTheme {
                        PhotoRollScreen(repository, renderer, state.isAtLeast(Lifecycle.State.RESUMED),
                            publications, host.previewTrimEpoch,
                            importConditions = { observer.snapshot().developConditions(rendererAvailable = true) },
                            close = host::finish)
                    }
                }
            }
        }

    fun close(primary: Throwable?) {
        preserveSafetyCleanup(primary,
            { renderer.discardTransientInputs() },
            { GpuPhotoRollRetiredOwners.releaseBeforeOpen() },
            { AndroidManualOriginalTrialSaves.discardPreview(app).get(60, TimeUnit.SECONDS) },
            { observer.stop() },
            { instrumentation.runOnMainSync { camera?.close() } })
    }
}