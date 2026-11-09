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
    private var cancellationMode = false
    private var saveSafetyMode = false
    private var manualCancellationMode = false
    private var manualCancellationPlan = ManualCancellationPlan.A_GLOW
    private var singleMemoryArm = 0
    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) return@registerForActivityResult
        if (!busy.compareAndSet(false, true)) {
            status.text = "Previous attempt is still closing. Try again when it finishes."
            return@registerForActivityResult
        }
        val cancelMode = cancellationMode
        val manualMode = manualCancellationMode
        val manualPlan = manualCancellationPlan
        val memoryArm = singleMemoryArm
        val memoryMode = saveSafetyMode || memory.isChecked
        val reverseOrder = !saveSafetyMode && reverse.isChecked
        val selectedComparison = if (saveSafetyMode) DiagnosticComparison.SAVE_SAFETY
            else DiagnosticComparison.timingComparisons[comparison.selectedItemPosition]
        val active = RawDiagnosticRunner(applicationContext).also { runner = it }
        status.text = "Preparing private input. Leaving this screen cancels the session."
        executor.execute {
            var returned = false
            val report = try {
                val progress: (String) -> Unit = { message -> runOnUiThread { if (!isDestroyed) status.text = message } }
                (if (memoryArm != 0) active.runSingleMemory(uri, memoryArm, progress)
                else if (manualMode) active.runManualCancellation(uri, manualPlan, progress)
                else if (cancelMode) active.runCancellation(uri, if (selectedComparison == DiagnosticComparison.SAVE_SAFETY) selectedComparison else DiagnosticComparison.RAW4, progress)
                else active.run(uri, memoryMode, reverseOrder, selectedComparison, progress)).also { returned = true }
            } catch (_: Throwable) {
                "HOLD. Outcome is uncertain. Restart the app manually before another session. Private attempt data may remain."
            } finally { if (returned && active.resourcesQuiescent) busy.set(false) }
            runOnUiThread { if (!isDestroyed) { if (active.resourcesQuiescent) runner = null; status.text = report } }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        cancellationMode = savedInstanceState?.getBoolean("cancellationMode") ?: false
        saveSafetyMode = savedInstanceState?.getBoolean("saveSafetyMode") ?: false
        manualCancellationMode = savedInstanceState?.getBoolean("manualCancellationMode") ?: false
        manualCancellationPlan = ManualCancellationPlan.entries.firstOrNull {
            it.name == savedInstanceState?.getString("manualCancellationPlan")
        } ?: ManualCancellationPlan.A_GLOW
        singleMemoryArm = savedInstanceState?.getInt("singleMemoryArm")?.takeIf { it == 1 || it == 2 } ?: 0
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        layout.addView(TextView(this).apply {
            text = "Save candidate safety only: Foundation2/Orton4/Character2 fixed. A: Glow1/Mist1/tone1; B: Glow2/Mist2/tone2. Color fusion OFF in both. The safety buttons ignore the legacy controls below. No speed rerun. Select only an authorized DNG; no shared JPEG is saved. Stay here; leaving cancels. Automatic cancellation checks run B twice, after native decode and Foundation, not during parallel workers."
        })
        layout.addView(Button(this).apply {
            text = "Save safety: Memory A/B (2 attempts)"
            setOnClickListener { selectInput(cancel = false, safety = true) }
        })
        for (arm in 1..2) {
            layout.addView(Button(this).apply {
                text = "Save safety: Memory ${if (arm == 1) "A" else "B"} (1 attempt)"
                setOnClickListener { selectInput(cancel = false, safety = true, memoryArm = arm) }
            })
        }
        layout.addView(TextView(this).apply {
            text = "Single Memory A/B: cool and check the same input/build/settings and starting conditions between sessions. Same 100 ms sampler; no true peak guarantee. Cross-arm JPEG equality is not checked across sessions; keep Memory A/B (2 attempts) for that evidence. Cooling does not reset allocator/GC state."
        })
        layout.addView(Button(this).apply {
            text = "Save safety: 2 cancellation checks"
            setOnClickListener { selectInput(cancel = true, safety = true) }
        })
        ManualCancellationPlan.entries.forEach { plan ->
            layout.addView(Button(this).apply {
                text = plan.label
                setOnClickListener { selectInput(cancel = false, safety = true, manual = plan) }
            })
        }
        layout.addView(TextView(this).apply {
            text = "Manual mode runs the selected arm once. Wait for the selected GLOW or MIST, then press Cancel. Compare A/B separately for each target. Stage display can lag; receipt stage and elapsed time are recorded. Equal elapsed time does not prove equal worker progress. 2s/5s are reference only; worse stop/release or unmatched conditions cannot pass. Timing starts in the button handler, not at physical touch. Missed stages, lifecycle cancellation, success and held owners are not target-stage cancellation success."
        })
        layout.addView(TextView(this).apply {
            text = "Legacy comparisons below, including RAW4 with color fusion ON in B."
        })
        layout.addView(TextView(this).apply {
            text = "Private RAW diagnostic\nChoose only a DNG you authorize for this test. The app copies it privately and runs 10 fresh attempts: 2 warm-ups, then ABBA twice (4 per arm). This may take several minutes. Fixed strength 0.5; no adjustment. No shared JPEG is saved. Failure stops the session and may retain private scratch.\nStay here; Back, rotation or background cancels. This is not a production Save/FGS test. Compare both orders after cooling. 10% stage-median threshold is a performance screen, not a safety approval. Use follow-up comparisons only for order disagreement or R7 improvement. Memory mode runs only 2 attempts. Optional cancellation checks are separate: 2 attempts, no speed result; failed scratch is intentionally kept private."
        })
        memory = CheckBox(this).apply { text = "Memory observations (100 ms; separate from timing; PSS before/after; 2 attempts total)" }
        reverse = CheckBox(this).apply { text = "Reverse order: BA / BAAB twice" }
        comparison = Spinner(this).apply {
            adapter = ArrayAdapter(this@RawDiagnosticActivity, android.R.layout.simple_spinner_dropdown_item,
                DiagnosticComparison.timingComparisons.map { it.label })
        }
        layout.addView(comparison); layout.addView(memory); layout.addView(reverse)
        layout.addView(Button(this).apply {
            text = "Select authorized DNG and start"
            setOnClickListener { selectInput(cancel = false, safety = false) }
        })
        layout.addView(Button(this).apply {
            text = "Select DNG for 2 cancellation checks"
            setOnClickListener { selectInput(cancel = true, safety = false) }
        })
        layout.addView(Button(this).apply {
            text = "Cancel"
            setOnClickListener { runner?.cancelFromUser(); status.text = "Cancellation requested; waiting for resources to close." }
        })
        status = TextView(this).apply { text = if (busy.get()) "Previous attempt is closing." else "Ready"; setTextIsSelectable(true) }
        layout.addView(status)
        setContentView(ScrollView(this).apply { addView(layout) })
    }
    private fun selectInput(cancel: Boolean, safety: Boolean, manual: ManualCancellationPlan? = null, memoryArm: Int = 0) {
        if (busy.get()) status.text = "An attempt is running or closing. Please wait."
        else {
            cancellationMode = cancel
            saveSafetyMode = safety
            singleMemoryArm = memoryArm
            manualCancellationMode = manual != null
            if (manual != null) manualCancellationPlan = manual
            picker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "*/*"; addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            })
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("cancellationMode", cancellationMode)
        outState.putBoolean("saveSafetyMode", saveSafetyMode)
        outState.putBoolean("manualCancellationMode", manualCancellationMode)
        outState.putString("manualCancellationPlan", manualCancellationPlan.name)
        outState.putInt("singleMemoryArm", singleMemoryArm)
        super.onSaveInstanceState(outState)
    }
    override fun onStop() {
        runner?.cancel()
        super.onStop()
    }
    companion object {
        // Process-wide gate survives Activity recreation until the current owner's finally closes it.
        private val busy = AtomicBoolean()
        /** 統合診断の入口用。取消中・HOLDも含み、実行状態は変更しない。 */
        internal fun activeOrHeld(): Boolean = busy.get()
        private val executor = Executors.newSingleThreadExecutor()
    }
}
