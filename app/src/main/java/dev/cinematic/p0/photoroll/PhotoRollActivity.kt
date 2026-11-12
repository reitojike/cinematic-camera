package dev.cinematic.p0.photoroll

import android.os.Bundle
import android.os.Build
import android.content.ComponentCallbacks2
import androidx.lifecycle.ViewModelProvider
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.cinematic.p0.CaptureTheme
import dev.cinematic.p0.rawcapture.DevelopExecution

class PhotoRollActivity : ComponentActivity() {
    private var foreground by mutableStateOf(false)
    private var gpuTrimEpoch by mutableStateOf(0)
    internal val previewTrimEpoch: Int get() = gpuTrimEpoch
    private val gpuPublications = kotlinx.coroutines.flow.MutableStateFlow(dev.cinematic.p0.gpu.preview.GpuInputPublication())
    private lateinit var resourceObserver: PhotoRollResourceObserver
    private lateinit var renderer: PhotoRollRenderer
    private lateinit var previewCache: PhotoRollPreviewCache

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = PhotoRollRepository(contentResolver, packageName)
        previewCache = ViewModelProvider(this)[PhotoRollPreviewCache::class.java]
        resourceObserver = PhotoRollResourceObserver(applicationContext, DevelopExecution::updateConditions,
            onPublication = { observation ->
                gpuPublications.value = dev.cinematic.p0.gpu.preview.GpuInputPublication(gpuPublications.value.sequence + 1, observation)
            })
        renderer = if (Build.VERSION.SDK_INT >= 29)
            ManualTrialPhotoRollRenderer(applicationContext, resourceObserver::snapshot, previewCache)
        else object : PhotoRollRenderer {
            override val mode = PhotoRollPreviewMode.PRIVATE_TRIAL
            override fun render(request: PhotoRollRenderRequest, cancellation: android.os.CancellationSignal) =
                PhotoRollRenderResult.Unavailable("私有試験JPEGの現像はAndroid 10以降で利用できます")
        }
        setContent {
            CaptureTheme { PhotoRollScreen(repository, renderer, foreground, gpuPublications, gpuTrimEpoch,
                importConditions = { resourceObserver.snapshot().developConditions(rendererAvailable = true) }, close = ::finish) }
        }
    }

    override fun onResume() {
        super.onResume()
        val current = renderer as? ManualTrialPhotoRollRenderer
        if (current != null && current.developEngine != dev.cinematic.p0.gpu.DevelopEngineSetting(this).current()) {
            current.discardTransientInputs()
            renderer = ManualTrialPhotoRollRenderer(applicationContext, resourceObserver::snapshot, previewCache)
        }
        foreground = true; resourceObserver.foreground(true)
    }
    override fun onPause() { foreground = false; renderer.discardTransientInputs(); resourceObserver.foreground(false); super.onPause() }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onLowMemory() { if (::previewCache.isInitialized) previewCache.clear(); if (::renderer.isInitialized) renderer.discardTransientInputs(); gpuTrimEpoch++; super.onLowMemory() }
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onTrimMemory(level: Int) {
        // UI_HIDDEN is normal navigation, not a memory-pressure signal. Raw inputs still use their own policy.
        if (::previewCache.isInitialized && level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) previewCache.clear()
        if (::renderer.isInitialized) {
            val gpu = (renderer as? ManualTrialPhotoRollRenderer)?.developEngine == dev.cinematic.p0.gpu.DevelopEngine.GPU
            if (!gpu || level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) renderer.discardTransientInputs()
            if (gpu && level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) gpuTrimEpoch++
        }
        super.onTrimMemory(level)
    }
    override fun onStart() { super.onStart(); resourceObserver.start() }
    override fun onStop() { resourceObserver.stop(); super.onStop() }
}
