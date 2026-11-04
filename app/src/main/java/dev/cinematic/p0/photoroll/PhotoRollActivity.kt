package dev.cinematic.p0.photoroll

import android.os.Bundle
import android.os.Build
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
    private lateinit var resourceObserver: PhotoRollResourceObserver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = PhotoRollRepository(contentResolver, packageName)
        resourceObserver = PhotoRollResourceObserver(applicationContext, DevelopExecution::updateConditions)
        val renderer: PhotoRollRenderer = if (Build.VERSION.SDK_INT >= 29)
            PrivateTrialPhotoRollRenderer(applicationContext, resourceObserver::snapshot)
        else object : PhotoRollRenderer {
            override val mode = PhotoRollPreviewMode.PRIVATE_TRIAL
            override fun render(request: PhotoRollRenderRequest, cancellation: android.os.CancellationSignal) =
                PhotoRollRenderResult.Unavailable("私有試験JPEGの現像はAndroid 10以降で利用できます")
        }
        setContent {
            CaptureTheme { PhotoRollScreen(repository, renderer, foreground, ::finish) }
        }
    }

    override fun onResume() { super.onResume(); foreground = true; resourceObserver.foreground(true) }
    override fun onPause() { foreground = false; resourceObserver.foreground(false); super.onPause() }
    override fun onStart() { super.onStart(); resourceObserver.start() }
    override fun onStop() { resourceObserver.stop(); super.onStop() }
}
