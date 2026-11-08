package dev.cinematic.p0.photoroll

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.Text
import dev.cinematic.p0.CaptureTheme

/** 合成fixtureだけの非公開host。製品Activityのcamera監視や共有一覧の初期化を始めない。 */
class PreviewPresentationActivity : ComponentActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        enableEdgeToEdge()
        setContent { CaptureTheme { Text("合成プレビュー診断。試験fixtureの接続待ちです。") } }
    }
}
