package dev.cinematic.p0.rawdiagnostic

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Only declared in rawDiagnostic. Leaving the foreground cooperatively cancels, never resumes silently. */
class RawDiagnosticActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var memory: CheckBox
    private lateinit var reverse: CheckBox
    private lateinit var comparison: Spinner
    private var runner: RawDiagnosticRunner? = null
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) return@registerForActivityResult
        if (!busy.compareAndSet(false, true)) {
            status.text = "Previous attempt is still closing. Try again when it finishes."
            return@registerForActivityResult
        }
        val memoryMode = memory.isChecked
        val reverseOrder = reverse.isChecked
        val selectedComparison = DiagnosticComparison.entries[comparison.selectedItemPosition]
        val active = RawDiagnosticRunner(applicationContext).also { runner = it }
        status.text = "Preparing private input. Leaving this screen cancels the session."
        executor.execute {
            var returned = false
            val report = try {
                active.run(uri, memoryMode, reverseOrder, selectedComparison) { progress ->
                    runOnUiThread { if (!isDestroyed) status.text = progress }
                }.also { returned = true }
            } catch (_: Throwable) {
                "HOLD. Outcome is uncertain. Restart the app manually before another session. Private attempt data may remain."
            } finally { if (returned && active.resourcesQuiescent) busy.set(false) }
            runOnUiThread { if (!isDestroyed) { if (active.resourcesQuiescent) runner = null; status.text = report } }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        layout.addView(TextView(this).apply {
            text = "Private RAW diagnostic\nChoose only a DNG you authorize for this test. The app copies it privately and runs 8 fresh attempts: 2 warm-ups, then 3 per arm. This may take several minutes. Fixed strength 0.5; no adjustment. No shared JPEG is saved. Failure stops the session and may retain private scratch.\nStay here; Back, rotation or background cancels. This is not a production Save/FGS test."
        })
        memory = CheckBox(this).apply { text = "Memory boundary observations (separate from timing)" }
        reverse = CheckBox(this).apply { text = "Reverse order: BA / BAABBA" }
        comparison = Spinner(this).apply {
            adapter = ArrayAdapter(this@RawDiagnosticActivity, android.R.layout.simple_spinner_dropdown_item,
                DiagnosticComparison.entries.map { it.label })
        }
        layout.addView(comparison); layout.addView(memory); layout.addView(reverse)
        layout.addView(Button(this).apply {
            text = "Select authorized DNG and start"
            setOnClickListener {
                if (busy.get()) status.text = "An attempt is running or closing. Please wait."
                else picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                })
            }
        })
        layout.addView(Button(this).apply {
            text = "Cancel"
            setOnClickListener { runner?.cancel(); status.text = "Cancellation requested; waiting for resources to close." }
        })
        status = TextView(this).apply { text = if (busy.get()) "Previous attempt is closing." else "Ready"; setTextIsSelectable(true) }
        layout.addView(status)
        setContentView(ScrollView(this).apply { addView(layout) })
    }
    override fun onStop() {
        runner?.cancel()
        super.onStop()
    }
    companion object {
        // Process-wide gate survives Activity recreation until the current owner's finally closes it.
        private val busy = AtomicBoolean()
        private val executor = Executors.newSingleThreadExecutor()
    }
}
