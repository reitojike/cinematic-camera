package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.os.Debug
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.CaptureTheme
import dev.cinematic.p0.jpegrender.original.*
import dev.cinematic.p0.photoedit.PhotoAdjustmentPolicy
import dev.cinematic.p0.rawcapture.*
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real snapshot HALF/FULL -> real GPU Surface + Compose. No product editor/provider/save owner.
 * ABBA is exploratory emulator timing, not a Pixel latency or percentile claim. The separate
 * visual run holds HALF for capture and is explicitly excluded from performance comparisons. */
class InitialPreviewSurfaceTimingAndroidTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    @get:Rule val fixture=SyntheticFixtureRule()
    private class Times(val fallback: Boolean, val captureHold: Boolean) {
        val start=AtomicLong(); val halfStart=AtomicLong(); val halfLinear=AtomicLong()
        val bitmapReady=AtomicLong(); val guidePublished=AtomicLong(); val composeRecord=AtomicLong()
        val fullStart=AtomicLong(); val fullLinear=AtomicLong(); val presented=AtomicLong()
        val memoryBefore=memorySample()
        var memoryAfter: JSONObject?=null
        fun json()=JSONObject().put("fallback",fallback).put("captureHoldExcluded",captureHold).also { out ->
            listOf("halfNativeStart" to halfStart,"halfLinearReady" to halfLinear,
                "bitmapReady" to bitmapReady,"guideUiPublished" to guidePublished,
                "composeDrawRecorded" to composeRecord,"fullNativeStart" to fullStart,
                "fullLinearReady" to fullLinear,"exactGpuPresented" to presented).forEach { (name,value) ->
                out.put(name+"Ms",if(value.get()==0L) JSONObject.NULL else (value.get()-start.get())/1_000_000.0)
            }
            out.put("composeDrawMeaning","drawContent returned; not display-composition completion")
            out.put("memoryBefore",memoryBefore).put("memoryAfter",memoryAfter)
            out.put("memoryMeaning","samples only, no forced GC, no total allocation cap claim")
        }
    }
    @Test fun halfBitmapOverlayThenExactPresentedWithAbbaControlAndSeparateCaptures() {
        requireGpuInputSafetyFixture()
        val context=instrumentation.targetContext
        val root=File.createTempFile("initial-guide-surface-",".tmp",context.cacheDir.canonicalFile)
        check(root.delete() && root.mkdir())
        val input=File(root,"synthetic.dng")
        input.outputStream().use { writeSyntheticTrialDng(it,1536,1024,1) }
        val source=SharedOriginalReference("content://synthetic/initial-guide",
            DngContentVerifier.identify(input.length(),{input.inputStream()}),OriginalGrant.USER_SELECTED)
        val geometry=ParcelFileDescriptor.open(input,ParcelFileDescriptor.MODE_READ_ONLY).use {
            Original24Preflight.inspect(it.fd,CancellationSignal())
        }
        val configured=InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
        val outputRoot=configured?.let(::File) ?: File(context.getExternalFilesDir(null),"additional_test_output")
        val outputs=File(outputRoot,"initial-preview-surface").apply { check(isDirectory || mkdirs()) }
        val results=JSONArray()
        var success=false
        try {
            for((index,fallback) in listOf(false,true,true,false).withIndex()) {
                results.put(run(source,geometry,input,File(root,"run-$index"),fallback,false,outputs).json())
            }
            // Deterministically capture a visible guide; this extra hold is NOT in ABBA results.
            val visual=run(source,geometry,input,File(root,"visual"),true,true,outputs)
            JSONObject().put("order","ABBA; A=FULL only, B=HALF guide then FULL")
                .put("inputWidth",1536).put("inputHeight",1024)
                .put("scope","after preflight/surface readiness; includes session snapshot copy, decode, bitmap, Compose and GPU; not full editor selection latency")
                .put("runs",results)
                .put("visualRunNotPerformance",visual.json()).toString(2).let {
                    File(outputs,"timings.json").writeText(it)
                }
            DngContentVerifier.verify(source.fingerprint,{input.inputStream()})
            success=true
        } finally {
            GpuPhotoRollRetiredOwners.releaseBeforeOpen()
            if(success) { assertTrue(input.delete()); assertTrue(root.delete()) }
        }
    }

    private fun run(source: SharedOriginalReference, geometry: Original24PreflightGeometry,
        input: File, scratch: File, fallback: Boolean, capture: Boolean, output: File): Times {
        val times=Times(fallback,capture)
        val owner=GpuPhotoRollSession(instrumentation.targetContext.assets)
        owner.presentedForDiagnostics={ times.presented.compareAndSet(0,SystemClock.elapsedRealtimeNanos()); Unit }
        val guide=mutableStateOf<InitialPreviewFrame?>(null)
        val values=PhotoAdjustmentPolicy.defaults
        val bound=checkNotNull(TrialStrengthEditor.empty(values).bind(source))
        val recipe=ManualOriginalTrialRender.recipe(values.strength,PhotoAdjustmentPolicy.adjustments(values))
        val identity=InitialPreviewIdentity(source,geometry,recipe.schema+":"+recipe.payload)
        val handler=Handler(Looper.getMainLooper())
        val handoff=InitialPreviewHandoff({handler.post(it)},{owner.presented.value==null}) {
            guide.value=it; times.guidePublished.compareAndSet(0,SystemClock.elapsedRealtimeNanos())
        }
        val allocated=mutableListOf<Bitmap>() // Test-owned references; recycled only after Activity destruction.
        var guideCapture: Bitmap?=null
        fixture.content={
            val presented by owner.presented.collectAsState()
            val exact=presented==values
            LaunchedEffect(exact) { if(exact) { handoff.close(); guide.value=null } }
            val visible=guide.value.takeUnless { exact }
            val editor=if(exact) bound.shown(bound.ticket,values) else bound
            CaptureTheme {
                PhotoRollDevelopView("SYNTHETIC INITIAL PREVIEW",PhotoRollPreviewMode.MANUAL_TRIAL,
                    editor,{editor},if(exact) PhotoRollPhase.DISPLAYED else PhotoRollPhase.CHECKING,
                    null,null,null,null,null,true,null,false,false,exact,
                    {},{},{},{},{_,_,_->},{},{},{},{},
                    modifier=Modifier.fillMaxSize().safeDrawingPadding(),
                    viewportModifier=Modifier.drawWithContent {
                        drawContent()
                        if(visible!=null) times.composeRecord.compareAndSet(0,SystemClock.elapsedRealtimeNanos())
                    },gpuSession=owner,gpuReady=exact,gpuPresentedValues=presented,initialGuide=visible,info={})
            }
        }
        try {
            ActivityScenario.launch(PhotoRollActivity::class.java).use { scenario ->
                await { owner.renderer.surfaceAttached.value }
                val callback: ((Int,CancellationSignal,()->dev.cinematic.p0.raw.LinearSrgbImage)->Unit)? =
                    if(!fallback) null else { _,signal,half ->
                        val linear=half()
                        val pixels=TrialDisplayPixels.encode(linear,1024) { signal.isCanceled }
                        val bitmap=Bitmap.createBitmap(pixels,linear.width,linear.height,Bitmap.Config.ARGB_8888)
                        allocated.add(bitmap)
                        times.bitmapReady.set(SystemClock.elapsedRealtimeNanos())
                        handoff.offer(InitialPreviewFrame(bitmap,InitialPreviewKind.HALF_GUIDE,identity))
                        if(capture) {
                            await { times.composeRecord.get()!=0L }
                            assertNotNull(find("RAW基準の案内画像・調整は未反映"))
                            assertFalse("comparison disabled before exact",hasComparisonAction())
                            guideCapture=screen(File(output,"guide.png"))
                        }
                    }
                times.start.set(SystemClock.elapsedRealtimeNanos())
                val exact=Original24PreviewSession(source,scratch,{},geometry,
                    beforeExactDecode=callback,
                    observeDecode={mode,start ->
                        val field=if(mode==PreviewRasterMode.FULL) {
                            if(start) times.fullStart else times.fullLinear
                        } else if(start) times.halfStart else times.halfLinear
                        field.compareAndSet(0,SystemClock.elapsedRealtimeNanos())
                    }) { input.inputStream() }.use { it.loadGpuAsShotInput() }
                owner.submit(values)
                owner.renderer.openPhoto(exact)
                await { owner.failure.value?.let { throw AssertionError("Exact GPU failure",it) }
                    owner.presented.value==values && times.presented.get()!=0L && guide.value==null }
                assertTrue(times.fullStart.get()>times.start.get())
                assertTrue(times.fullLinear.get()>=times.fullStart.get())
                assertTrue(times.presented.get()>times.fullLinear.get())
                if(fallback) {
                    assertTrue(times.halfLinear.get()>=times.halfStart.get())
                    assertTrue(times.bitmapReady.get()>=times.halfLinear.get())
                    assertTrue(times.fullStart.get()>=times.bitmapReady.get())
                } else assertEquals(0L,times.bitmapReady.get())
                if(capture) {
                    await { hasComparisonAction() }
                    assertNull(find("RAW基準の案内画像・調整は未反映"))
                    val exactCapture=screen(File(output,"exact.png"))
                    try { assertFalse("guide and exact display differ",requireNotNull(guideCapture).sameAs(exactCapture)) }
                    finally { exactCapture.recycle() }
                    // Real surface lifecycle, not a source/editor retry simulation.
                    scenario.moveToState(Lifecycle.State.CREATED)
                    await { !owner.renderer.surfaceAttached.value }
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    await { owner.renderer.surfaceAttached.value && owner.presented.value==values }
                    assertNull(find("RAW基準の案内画像・調整は未反映"))
                }
            }
        } finally {
            handoff.close(); owner.presentedForDiagnostics=null; owner.close()
            assertTrue(owner.renderer.awaitClosed(10_000))
            // awaitClosed also checks ledger bytes/textures/windows, retained input and photo owners.
            GpuPhotoRollRetiredOwners.releaseBeforeOpen()
            guideCapture?.recycle()
            allocated.forEach { if(!it.isRecycled) it.recycle() }
            times.memoryAfter=memorySample()
        }
        return times
    }
    companion object {
        private fun memorySample(): JSONObject {
            val runtime=Runtime.getRuntime()
            return JSONObject().put("pssKb",Debug.getPss()).put("nativeHeapBytes",Debug.getNativeHeapAllocatedSize())
                .put("javaUsedBytes",runtime.totalMemory()-runtime.freeMemory())
        }
    }
    private fun screen(file: File): Bitmap {
        instrumentation.waitForIdleSync()
        val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "Composited screenshot unavailable" }
        file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        return bitmap
    }
    private fun hasComparisonAction(): Boolean = find("現像結果（表示用）")?.actionList?.any {
        it.label?.toString()?.contains("RAW")==true
    } == true
    private fun find(label: String): AccessibilityNodeInfo? {
        fun walk(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if(node==null) return null
            if(node.text?.toString()==label || node.contentDescription?.toString()==label) return node
            for(i in 0 until node.childCount) walk(node.getChild(i))?.let { return it }
            return null
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
        error("Initial guide surface condition timed out")
    }
}
