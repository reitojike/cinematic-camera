package dev.cinematic.p0.halfcompare

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.widget.*
import dev.cinematic.p0.BuildConfig
import java.util.concurrent.Executors

/** Explicit private comparison only. No persistent grant, product state, JPEG export or network. */
class PreviewCompareActivity : Activity() {
    private val generation = CompareGeneration()
    companion object {
        private val executor = Executors.newSingleThreadExecutor()
        @Volatile internal var createComparison: (android.content.Context) -> PrivatePhotoComparison = { PrivatePhotoComparison(it) }
    }
    private var stop: ComparisonStop? = null
    private var pair: PrivatePhotoComparison.Pair? = null
    private lateinit var status: TextView
    private lateinit var image: ImageView
    private lateinit var exposure: Spinner
    private var selectedEv = 0.0

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        selectedEv = state?.getDouble("selectedEv", 0.0) ?: 0.0
        check(BuildConfig.DEBUG && BuildConfig.PRIVATE_HALF_COMPARE && packageName.endsWith(".halfcompare"))
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 16, 16, 16) }
        val padding = (16 * resources.displayMetrics.density).toInt()
        column.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
            insets
        }
        column.addView(TextView(this).apply { text = "HALF 比較（未採用・保存なし）\n同じ写真を FULL / HALF で比較します。原本と普段のアプリは変更しません。\n端末内のDNGのみ選択してください。クラウドは対象外です。" })
        exposure = Spinner(this).apply {
            adapter = ArrayAdapter(this@PreviewCompareActivity, android.R.layout.simple_spinner_dropdown_item,
                arrayOf("露出 0 EV", "露出 −1 EV", "露出 −2 EV"))
        }
        exposure.setSelection((-selectedEv).toInt())
        column.addView(exposure)
        column.addView(Button(this).apply { text = "DNG を選ぶ"; setOnClickListener {
            cancelAndClear()
            selectedEv = listOf(0.0, -1.0, -2.0)[exposure.selectedItemPosition]
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, 1)
        } })
        val controls = LinearLayout(this)
        controls.addView(Button(this).apply { text = "FULL"; setOnClickListener { pair?.let { image.setImageBitmap(it.full); status.text = "FULL · $selectedEv EV · 強さ 0.5" } } })
        controls.addView(Button(this).apply { text = "HALF"; setOnClickListener { pair?.let { image.setImageBitmap(it.half); status.text = "HALF · $selectedEv EV · 強さ 0.5" } } })
        controls.addView(Button(this).apply { text = "閉じる"; setOnClickListener { cancelAndClear(); finish() } })
        column.addView(controls)
        status = TextView(this).apply { text = "写真を選んでください。既存の調整値は使用しません。" }
        column.addView(status)
        image = CompareImageView(this).apply { setBackgroundColor(Color.BLACK) }
        column.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(column)
        column.requestApplyInsets()
    }

    @Deprecated("Legacy Activity result for standalone evaluation")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1 && resultCode == RESULT_OK) data?.data?.let(::compare)
    }

    private fun compare(uri: Uri) {
        cancelAndClear()
        val ticket = generation.next()
        val cancellation = ComparisonStop().also { stop = it }
        val ev = selectedEv
        status.text = "比較を準備しています。"
        executor.execute {
            try {
                val result = createComparison(applicationContext).compare(uri, cancellation, ev) { message ->
                    runOnUiThread { if (generation.accepts(ticket) && !isDestroyed) status.text = message }
                }
                runOnUiThread {
                    if (generation.accepts(ticket) && !isDestroyed) {
                        pair = result; image.setImageBitmap(result.full)
                        status.text = "FULL · $ev EV · 強さ 0.5。ボタンで同じ位置を比較してください。"
                    } else result.close()
                }
            } catch (error: Exception) {
                runOnUiThread { if (generation.accepts(ticket) && !isDestroyed) status.text = when {
                    PrivatePhotoComparison.cleanupBlocked -> "一時データを解放できません。端末のアプリ情報で比較アプリを強制停止してから、起動し直してください。"
                    error.message == "COMPARE_MEMORY" -> "メモリ不足で比較を始められません。しばらくしてから再試行してください。"
                    else -> "比較を続けられません。原本は変更していません。別の写真でやり直してください。"
                } }
            } catch (_: OutOfMemoryError) {
                runOnUiThread { if (generation.accepts(ticket) && !isDestroyed) status.text = "メモリ不足で比較を続けられません。原本は変更していません。" }
            }
        }
    }

    private fun cancelAndClear() {
        generation.next(); stop?.cancel(); stop = null
        if (::image.isInitialized) image.setImageBitmap(null)
        pair?.close(); pair = null
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putDouble("selectedEv", selectedEv); super.onSaveInstanceState(outState) }
    override fun onStop() { cancelAndClear(); super.onStop() }
    override fun onDestroy() { cancelAndClear(); super.onDestroy() }
}
