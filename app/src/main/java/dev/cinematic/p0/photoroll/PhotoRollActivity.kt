package dev.cinematic.p0.photoroll

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.cinematic.p0.CaptureTheme

class PhotoRollActivity : ComponentActivity() {
    private var foreground by mutableStateOf(false)
    private lateinit var resourceObserver: PhotoRollResourceObserver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = PhotoRollRepository(contentResolver, packageName)
        resourceObserver = PhotoRollResourceObserver(applicationContext)
        val renderer: PhotoRollRenderer = DngTrialRenderer(contentResolver, resourceObserver::snapshot)
        setContent {
            CaptureTheme { PhotoRollScreen(repository, renderer, foreground, ::finish) }
        }
    }

    override fun onResume() { super.onResume(); foreground = true }
    override fun onPause() { foreground = false; super.onPause() }
    override fun onStart() { super.onStart(); resourceObserver.start() }
    override fun onStop() { resourceObserver.stop(); super.onStop() }
}
