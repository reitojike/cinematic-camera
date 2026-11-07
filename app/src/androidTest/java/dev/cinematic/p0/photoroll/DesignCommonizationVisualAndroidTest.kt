package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.*
import dev.cinematic.p0.photoedit.PhotoAdjustmentPolicy
import dev.cinematic.p0.rawcapture.OriginalFingerprint
import dev.cinematic.p0.rawcapture.OriginalGrant
import dev.cinematic.p0.rawcapture.SharedOriginalReference
import java.io.File
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

/**
 * Same test source is run on baseline eb99733 and the candidate. Only synthetic data is used.
 * PNGs exclude system bars and are software View-tree evidence, not GPU/display composition.
 * Capture cases cover the actual shared chrome/key/ruler components, NOT CameraProbe screens.
 * Settings and diagnostic screen matrices require a separate presentation seam; not covered here.
 */
class DesignCommonizationVisualAndroidTest {
    @get:org.junit.Rule val fixture = SyntheticFixtureRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var bounds: Rect? = null
    private val active = mutableStateOf("")
    private var syntheticAfter: Bitmap? = null
    private var syntheticBefore: Bitmap? = null
    private val editor = requireNotNull(TrialStrengthEditor.empty(PhotoAdjustmentPolicy.defaults).bind(
        SharedOriginalReference("content://synthetic-design/original", OriginalFingerprint(100, "a".repeat(64)),
            OriginalGrant.USER_SELECTED))).let { it.shown(it.ticket, it.requestedValues) }

    @Test fun stableSyntheticMatrixForBeforeAfterComparison() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        syntheticAfter = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(86, 120, 135))
        }
        syntheticBefore = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply {
            eraseColor(android.graphics.Color.rgb(135, 120, 86))
        }
        val cases = listOf("capture-components-vertical", "capture-components-horizontal",
            "roll-list", "roll-partial", "roll-empty",
            "develop-display", "develop-adjusting", "develop-saving", "develop-private", "develop-disabled")
        fixture.content = {
            BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
                val native = LocalDensity.current.density
                val density = minOf(maxWidth.value / 412f, maxHeight.value / 924f) * native
                CompositionLocalProvider(LocalDensity provides Density(density, 1f)) {
                    CaptureTheme {
                        key(active.value) {
                            Box(Modifier.size(412.dp, 924.dp).background(CaptureDesign.Ink)
                                .semantics { contentDescription = "design-matrix-${active.value}" }
                                .onGloballyPositioned { coordinates ->
                                    val r = coordinates.boundsInWindow()
                                    bounds = Rect(r.left.roundToInt(), r.top.roundToInt(), r.right.roundToInt(), r.bottom.roundToInt())
                                }) {
                                when {
                                    active.value.startsWith("capture-") -> CaptureComponents(active.value.endsWith("horizontal"))
                                    active.value.startsWith("roll-") -> Roll(active.value)
                                    active.value.startsWith("develop-") -> Develop(active.value)
                                }
                            }
                        }
                    }
                }
            }
        }
        try {
            ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
                scenario.recreate()
                for (name in cases) {
                    scenario.onActivity { bounds = null; active.value = name }
                    awaitMarker(name)
                    saveStable(name)
                }
            }
        } finally {
            syntheticAfter?.recycle(); syntheticBefore?.recycle()
            syntheticAfter = null; syntheticBefore = null
        }
    }

    @Composable private fun CaptureComponents(horizontal: Boolean) {
        val rotation = if (horizontal) 90f else 0f
        val input = CaptureChromeInput(CaptureMode.STILL, bound = true, cameraId = "SYNTHETIC",
            manualExposure = false, exposureModePhase = ControlPhase.ACCEPTED, photoFormat = "RAW+JPEG",
            captureReady = true, thermalSeverity = 0)
        Column(Modifier.fillMaxSize()) {
            CaptureStatusBar(input, true, false, {}, rotation = rotation, horizontal = horizontal)
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("SYNTHETIC PREVIEW", color = CaptureDesign.Muted)
            }
            CaptureRulerHeading("ISO", {}, true, Modifier.background(CaptureDesign.Ruler), rotation, horizontal)
            Row(Modifier.fillMaxWidth()) {
                listOf(Triple("ISO", "100", "AUTO"), Triple("SHUTTER", "1/100", "AUTO"),
                    Triple("EV", "0.0", ""), Triple("WB", "AUTO", ""), Triple("FOCUS", "AF", ""),
                    Triple("LOOK", "CLEAR", "50")).forEachIndexed { index, (label, value, tag) ->
                    CaptureControlKey(label, value, tag, index == 0, true, {}, Modifier.weight(1f),
                        rotation = rotation, horizontal = horizontal)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically) {
                CaptureShutter(input, {}, rotation = rotation)
                CaptureShutter(input.copy(mode = CaptureMode.CINE, recordingStage = RecordingStage.RECORDING), {}, rotation = rotation)
                CaptureModeSwitchSurface(false) {
                    Text("STILL", Modifier.weight(1f), color = CaptureDesign.Ink)
                    Text("CINE", Modifier.weight(1f), color = CaptureDesign.Muted)
                }
            }
        }
    }

    @Composable private fun Roll(name: String) {
        val items = if (name == "roll-empty") emptyList() else listOf(
            PhotoRollItem("content://synthetic-design/missing-dng", "SYNTHETIC.dng"),
            PhotoRollItem("content://synthetic-design/missing-jpeg", "SYNTHETIC.jpg", CameraRollKind.PHOTO, "image/jpeg"))
        CameraRollBrowser(PhotoRollListing(items), if (name == "roll-partial") CameraRollAccess(true, false, false, true)
            else CameraRollAccess(true, true, true, false), PhotoRollKindFilter.ALL,
            null, false, false, null, PhotoRollPreviewMode.MANUAL_TRIAL,
            true, true, true, true, false, {}, {}, {}, {}, {}, {}, {}, {}, {}, Modifier.fillMaxSize(), {})
    }

    @Composable private fun Develop(name: String) {
        val adjusting = name == "develop-adjusting"
        val saving = name == "develop-saving"
        val reduced = name == "develop-private"
        val disabled = name == "develop-disabled"
        val state = if (adjusting) editor.begin(.01, true) else editor
        PhotoRollDevelopView("SYNTHETIC.dng", if (reduced) PhotoRollPreviewMode.PRIVATE_TRIAL else PhotoRollPreviewMode.STAGE_CLEAR,
            state, { state }, PhotoRollPhase.DISPLAYED, null, null,
            syntheticAfter, syntheticBefore, "合成基準表示", !disabled,
            if (saving) PhotoRollSavePhase.SAVING else PhotoRollSavePhase.READY, saving,
            !adjusting && !saving && !reduced && !disabled, !disabled,
            {}, {}, {}, {}, { _, _, _ -> }, {}, {}, {}, {}, Modifier.fillMaxSize()) {
            Text("合成情報のみ")
        }
    }

    private fun awaitMarker(name: String) {
        fun contains(node: AccessibilityNodeInfo?): Boolean = node != null &&
            (node.contentDescription?.toString() == "design-matrix-$name" ||
                (0 until node.childCount).any { contains(node.getChild(it)) })
        val deadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (contains(fixture.fixtureRoot()) && bounds?.let { it.width() > 0 && it.height() > 0 } == true) return
            SystemClock.sleep(50)
        }
        error("Synthetic matrix not ready: $name")
    }

    private fun crop(): Bitmap {
        val full = fixture.captureSoftware()
        try {
            val r = requireNotNull(bounds)
            check(r.left >= 0 && r.top >= 0 && r.right <= full.width && r.bottom <= full.height)
            val region = Bitmap.createBitmap(full, r.left, r.top, r.width(), r.height())
            try { return region.copy(Bitmap.Config.ARGB_8888, false) }
            finally { if (region !== full) region.recycle() }
        } finally { full.recycle() }
    }

    private fun saveStable(name: String) {
        var previous = crop()
        val deadline = SystemClock.uptimeMillis() + 5_000
        try {
            while (SystemClock.uptimeMillis() < deadline) {
                SystemClock.sleep(250)
                val next = crop()
                if (previous.sameAs(next)) {
                    next.recycle()
                    val pixels = IntArray(previous.width * previous.height)
                    previous.getPixels(pixels, 0, previous.width, 0, 0, previous.width, previous.height)
                    assertTrue("Blank synthetic matrix: $name", pixels.any { it != pixels.first() })
                    // AGP pulls this directory before a managed device is torn down.
                    val configured = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                    val outputRoot = if (configured != null) File(configured) else
                        File(requireNotNull(instrumentation.targetContext.externalMediaDirs.firstOrNull()), "additional_test_output")
                    val dir = File(outputRoot, "design-commonization").apply {
                        check(isDirectory || mkdirs()) { "Cannot create additional test output directory" }
                    }
                    File(dir, "$name.png").outputStream().use { assertTrue(previous.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    return
                }
                previous.recycle(); previous = next
            }
            error("Synthetic matrix did not settle: $name")
        } finally { previous.recycle() }
    }
}