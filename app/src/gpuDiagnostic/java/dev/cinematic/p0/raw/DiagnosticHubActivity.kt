package dev.cinematic.p0.raw

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.cinematic.p0.photoroll.PhotoRollActivity
import dev.cinematic.p0.rawdiagnostic.RawDiagnosticActivity

/** 診断の入口だけを集約。GPUを製品の描画・保存経路へ接続しない。 */
class DiagnosticHubActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(TextView(this).apply {
            text = "統合診断・製品未採用\nGPUは合成データのみ。保存比較とプレビューはFilesから明示的に選んだ入力だけを使います。"
        })
        listOf(
            "GPU合成診断" to GpuDiagnosticActivity::class.java,
            "保存4候補の比較" to RawDiagnosticActivity::class.java,
            "CPUプレビューの確認（Filesから選択）" to PhotoRollActivity::class.java,
        ).forEach { (label, target) ->
            panel.addView(Button(this).apply {
                text = label
                setOnClickListener {
                    if (GpuDiagnosticService.active() || dev.cinematic.p0.photoroll.ForegroundExports.gate.active() != null) {
                        android.widget.Toast.makeText(this@DiagnosticHubActivity, "実行中の診断・保存が終わってから切り替えてください", android.widget.Toast.LENGTH_SHORT).show()
                    } else startActivity(Intent(this@DiagnosticHubActivity, target))
                }
            })
        }
        setContentView(panel)
    }
}