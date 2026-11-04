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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val repository = PhotoRollRepository(contentResolver, packageName)
        // Replace only this dependency when the #78 display renderer contract is ready.
        val renderer: PhotoRollRenderer = UnconnectedPhotoRollRenderer
        setContent {
            CaptureTheme { PhotoRollScreen(repository, renderer, foreground, ::finish) }
        }
    }

    override fun onResume() { super.onResume(); foreground = true }
    override fun onPause() { foreground = false; super.onPause() }
}
