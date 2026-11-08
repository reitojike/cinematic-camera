package dev.cinematic.p0.raw

import android.app.Activity
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Bundle
import android.os.PowerManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import dev.cinematic.p0.photoroll.ForegroundExports
import dev.cinematic.p0.rawdiagnostic.RawDiagnosticActivity
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** 表示だけでは実行しない。退出・再作成時は取消し、終了まで排他状態を保つ。 */
class PreviewParallelDiagnosticActivity : Activity() {
    private val stop = AtomicBoolean(false)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var worker: Thread? = null
    private lateinit var result: TextView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(TextView(this).apply {
            text = "CPUプレビュー並列候補・合成のみ\n1024pxのFoundation / Orton / Characterを、それぞれ同一入力で直列と2並列で比較します。現像全体・操作から表示までの時間ではありません。写真を読まず、製品の既定は変えません。\n時間は計算・worker開始/終了を含み、合成素材の作成とハッシュ計算は含みません。反復・冷却を含む採否は別途判断します。"
        })
        for ((label, reverse) in listOf("合成1024px: 1→2並列" to false, "合成1024px: 2→1並列" to true)) {
            panel.addView(Button(this).apply { text = label; setOnClickListener { startComparison(reverse) } })
        }
        panel.addView(Button(this).apply { text = "取消"; setOnClickListener { cancel(); result.text = "取消待ち" } })
        result = TextView(this).apply { text = "未実行" }
        panel.addView(result)
        setContentView(ScrollView(this).apply { addView(panel) })
    }

    private fun startComparison(reverse: Boolean) {
        if (worker != null || RawDiagnosticActivity.activeOrHeld() || GpuDiagnosticService.active() ||
            ForegroundExports.gate.active() != null || !busy.compareAndSet(false, true)) {
            result.text = "実行中の診断・保存の終了後に開始してください"
            return
        }
        stop.set(false)
        cancellation = stop
        result.text = "実行中。終了までは他の診断へ切り替えないでください"
        try {
            worker = Thread({
                val report = try {
                    val before = environment()
                    val comparison = PreviewParallelDiagnostic.compare(reverse = reverse, shouldStop = stop::get)
                    "全3工程: raw-bit完全一致\n$before\n${environment()}\n" + comparison.samples.joinToString("\n") {
                        "${it.stage} workers=${it.workers} ms=${it.nanos / 1_000_000.0} sha256=${it.sha256}"
                    }
                } catch (_: CancellationException) { "取消完了" }
                catch (failure: Throwable) { "失敗: ${failure.javaClass.simpleName}" }
                val owner = Thread.currentThread()
                try { android.util.Log.i("PreviewParallelDiagnostic", report) }
                finally { main.post { finishWorker(owner, report) } }
            }, "preview-parallel-diagnostic").also { it.start() }
        } catch (failure: Throwable) {
            worker = null
            if (cancellation === stop) cancellation = null
            busy.set(false)
            result.text = "開始失敗: ${failure.javaClass.simpleName}"
        }
    }

    private fun finishWorker(owner: Thread, report: String) {
        // UIを止めずに実際のthread終了を確認し、取消待ち中の切替を防ぐ。
        if (owner.isAlive) {
            main.postDelayed({ finishWorker(owner, report) }, 10)
            return
        }
        if (worker === owner) {
            worker = null
            if (cancellation === stop) cancellation = null
            busy.set(false)
        }
        if (!isDestroyed && !isFinishing) result.text = report
    }

    private fun environment(): String {
        val power = getSystemService(PowerManager::class.java)
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return "thermal=${power.currentThermalStatus} batteryDeciC=${battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)} plugged=${battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)}"
    }

    override fun onStop() { stop.set(true); super.onStop() }
    override fun onDestroy() { stop.set(true); super.onDestroy() }

    companion object {
        private val busy = AtomicBoolean(false)
        @Volatile private var cancellation: AtomicBoolean? = null
        fun active(): Boolean = busy.get()
        fun cancel() { cancellation?.set(true) }
    }
}
