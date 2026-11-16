package dev.cinematic.p0.photoroll

import android.graphics.Rect
import android.view.MotionEvent
import java.util.concurrent.atomic.AtomicInteger
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.jpegrender.original.requireGpuInputSafetyFixture
import dev.cinematic.p0.CaptureTheme
import dev.cinematic.p0.photoedit.PhotoAdjustmentPolicy
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Product lookup -> callback -> main handoff -> Compose -> exact GPU presentation.
 * Synthetic, no shared writes or capture. The guide hold is intentional, not a timing sample. */
class InitialPreviewProductSurfaceAndroidTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    @get:Rule val fixture=SyntheticFixtureRule()

    @Test fun publishedGuideIsDrawnThenReplacedByExactAndCannotReturnAfterResume() {
        requireGpuInputSafetyFixture()
        InitialPreviewProductFixture(true).use { product ->
            product.publish()
            val owner=GpuPhotoRollSession(instrumentation.targetContext.assets)
            val values=PhotoAdjustmentPolicy.defaults
            val guide=mutableStateOf<InitialPreviewFrame?>(null)
            val saveCalls=AtomicInteger()
            val bound=checkNotNull(TrialStrengthEditor.empty(values).bind(product.source))
            val drawRecorded=AtomicBoolean()
            val presented=AtomicBoolean()
            val allocations=CopyOnWriteArrayList<Bitmap>()
            val handler=Handler(Looper.getMainLooper())
            owner.presentedForDiagnostics={ presented.set(true) }
            val handoff=InitialPreviewHandoff({ handler.post(it) },{ !presented.get() }) { guide.value=it }
            fixture.content={
                val current by owner.presented.collectAsState()
                val exact=current==values
                LaunchedEffect(exact) { if(exact) { handoff.close(); guide.value=null } }
                val visible=guide.value.takeUnless { exact }
                CaptureTheme {
                    val editor=if(exact) bound.shown(bound.ticket,values) else bound
                    PhotoRollDevelopView("SYNTHETIC PRODUCT INPUT",PhotoRollPreviewMode.MANUAL_TRIAL,
                        editor,{editor},if(exact) PhotoRollPhase.DISPLAYED else PhotoRollPhase.CHECKING,
                        null,null,null,null,null,true,null,false,false,exact,
                        {saveCalls.incrementAndGet()},{},{},{},{_,_,_->},{},{},{},{},
                        modifier=Modifier.fillMaxSize(),viewportModifier=Modifier.drawWithContent {
                            drawContent(); if(visible!=null) drawRecorded.set(true)
                        },gpuSession=owner,gpuReady=exact,gpuPresentedValues=current,initialGuide=visible,info={})
                }
            }
            try {
                ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
                    await { owner.renderer.surfaceAttached.value }
                    var offered: InitialPreviewFrame?=null
                    val loaded=product.open { frame ->
                        assertEquals(InitialPreviewKind.SAVED_JPEG,frame.kind)
                        offered=frame
                        allocations+=frame.bitmap
                        handoff.offer(frame)
                        // Real callback executes on input IO. FULL cannot start before it returns.
                        await { drawRecorded.get() && containsLabel(frame.label) }
                        // View gate only: production Editor canSave is independently guarded by
                        // ready/presented/metadata/save-owner; this fixture does not prove that gate.
                        val save=actionNode(checkNotNull(findLabel("JPEGを保存")))
                        assertFalse(save.isEnabled)
                        val bounds=Rect().also { save.getBoundsInScreen(it) }
                        val down=SystemClock.uptimeMillis()
                        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
                            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,
                                bounds.exactCenterX(),bounds.exactCenterY(),0)
                            try { assertTrue(fixture.automation.injectInputEvent(event,true)) } finally { event.recycle() }
                        }
                        instrumentation.waitForIdleSync()
                        assertEquals(0,saveCalls.get())
                        assertFalse(containsLabel("指定 · 未反映"))
                        assertFalse(containsLabel("表示中の値なし"))
                        assertFalse(presented.get())
                        assertFalse(frame.bitmap.isRecycled)
                    }
                    val saved=checkNotNull(offered)
                    assertEquals(product.source,loaded.original.reference)
                    owner.submit(loaded.values)
                    owner.renderer.openPhoto(loaded.input)
                    await {
                        owner.failure.value?.let { throw AssertionError("Product exact GPU failed",it) }
                        presented.get() && owner.presented.value==loaded.values && guide.value==null && !containsLabel(saved.label)
                    }
                    assertFalse("displayed pixels remain valid until Activity destruction",saved.bitmap.isRecycled)
                    val late=saved.copy(bitmap=checkNotNull(saved.bitmap.copy(Bitmap.Config.ARGB_8888,false)))
                    handoff.offer(late)
                    assertTrue("terminal exact handoff rejects late pixels",late.bitmap.isRecycled)
                    val rejected=saved.copy(bitmap=checkNotNull(saved.bitmap.copy(Bitmap.Config.ARGB_8888,false)))
                    InitialPreviewHandoff({false},{true},{fail("post failure must not publish")}).offer(rejected)
                    assertTrue(rejected.bitmap.isRecycled)
                    scenario.moveToState(Lifecycle.State.CREATED)
                    await { !owner.renderer.surfaceAttached.value }
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    await { owner.renderer.surfaceAttached.value && owner.presented.value==loaded.values }
                    assertNull(guide.value)
                    assertFalse(containsLabel(saved.label))
                    assertFalse(saved.bitmap.isRecycled)
                    product.verify()
                }
            } finally {
                // ActivityScenario is already destroyed; never recycle published pixels while UI owns them.
                handoff.close()
                instrumentation.runOnMainSync { guide.value=null; fixture.content=null }
                owner.presentedForDiagnostics=null
                owner.close()
                assertTrue("closed ledger and retained native input released",owner.renderer.awaitClosed(10_000))
                GpuPhotoRollRetiredOwners.releaseBeforeOpen()
                allocations.forEach { if(!it.isRecycled) it.recycle() }
            }
        }
    }
    /** Android may expose the label as a child of the actionable Compose node. */
    private fun actionNode(label: AccessibilityNodeInfo): AccessibilityNodeInfo {
        val labelBounds=Rect().also { label.getBoundsInScreen(it) }
        var current=label
        while(true) {
            check(current.refresh()) { "Stale action node" }
            if(current.isClickable) {
                val bounds=Rect().also { current.getBoundsInScreen(it) }
                check(bounds.contains(labelBounds)) { "Action node does not contain label" }
                return current
            }
            current=checkNotNull(current.parent) { "No actionable ancestor for save label" }
        }
    }
    private fun findLabel(label: String): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if(node==null) return null
            if(node.text?.toString()==label || node.contentDescription?.toString()==label) return node
            for(i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
        }
        fixture.automation.clearCache()
        return walk(fixture.fixtureRoot())
    }
    private fun containsLabel(label: String): Boolean {
        fun walk(node: AccessibilityNodeInfo?): Boolean {
            if(node==null) return false
            if(node.text?.toString()==label || node.contentDescription?.toString()==label || node.stateDescription?.toString()==label) return true
            return (0 until node.childCount).any { walk(node.getChild(it)) }
        }
        fixture.automation.clearCache()
        return walk(fixture.fixtureRoot())
    }
    private fun await(condition: ()->Boolean) {
        val until=SystemClock.uptimeMillis()+60_000
        do {
            instrumentation.waitForIdleSync()
            if(condition()) return
            SystemClock.sleep(20)
        } while(SystemClock.uptimeMillis()<until)
        error("Product guide Surface condition timed out")
    }
}
