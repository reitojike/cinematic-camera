package dev.cinematic.p0.photoroll

import android.graphics.Rect
import android.graphics.Color
import android.view.MotionEvent
import android.view.ViewConfiguration
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import dev.cinematic.p0.gpu.preview.GpuPreviewSnapshot
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
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
    private val viewportLayout=AtomicReference<Rect?>()
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

    /** Artifact-only capture for visual review. Never replaces the strict UI assertions above. */
    @Test fun captureSyntheticComparisonWithoutParameterNodeAssertions() {
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments()
            .getString("initialVisualCapture")=="true")
        requireGpuInputSafetyFixture()
        val context=instrumentation.targetContext
        val root=File.createTempFile("initial-guide-visual-",".tmp",context.cacheDir.canonicalFile)
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
        val outputs=File(outputRoot,"initial-preview-visual-only").apply { check(isDirectory || mkdirs()) }
        var success=false
        try {
            run(source,geometry,input,File(root,"visual"),true,true,outputs,verifyParameterNodes=false)
            DngContentVerifier.verify(source.fingerprint,{input.inputStream()})
            success=true
        } finally {
            GpuPhotoRollRetiredOwners.releaseBeforeOpen()
            if(success) { assertTrue(input.delete()); assertTrue(root.delete()) }
        }
    }

    /** Separate retention diagnostic. Forced GC here is observation only, never a product policy,
     * performance sample, pass threshold or proof of a total memory cap. */
    @Test fun postRunMemoryAfterFixtureReleaseAndDiagnosticGc() {
        requireGpuInputSafetyFixture()
        val context=instrumentation.targetContext
        val root=File.createTempFile("initial-guide-retention-",".tmp",context.cacheDir.canonicalFile)
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
        val report=JSONObject()
            .put("purpose","post-run retention diagnosis only; not ABBA performance results")
            .put("order","FULL, HALF+FULL repeated three times; capture disabled")
            .put("inputWidth",1536).put("inputHeight",1024)
            .put("interpretation","GC is requested, not guaranteed; samples have no threshold and do not prove a total cap. No weak-reference reachability measurement.")
            .put("baseline",memorySample()).put("runs",results)
        var success=false
        try {
            for((index,fallback) in listOf(false,true,false,true,false,true).withIndex()) {
                val before=memorySample()
                // Do not retain Times or any in-run exact/Bitmap locals in this diagnostic.
                run(source,geometry,input,File(root,"run-$index"),fallback,false,outputs)
                instrumentation.runOnMainSync { fixture.content=null }
                instrumentation.waitForIdleSync()
                val released=memorySample()
                SystemClock.sleep(1_000)
                instrumentation.waitForIdleSync()
                val idle=memorySample()
                // Diagnostic only: never included in the existing timing test or production code.
                System.gc()
                System.runFinalization()
                System.gc()
                SystemClock.sleep(1_000)
                instrumentation.waitForIdleSync()
                results.put(JSONObject().put("index",index).put("fallback",fallback)
                    .put("beforeRun",before).put("afterReturnAndFixtureRelease",released)
                    .put("afterOneSecondIdle",idle).put("afterDiagnosticGcAndOneSecondIdle",memorySample())
                    .put("closedRendererOwnershipChecksPassed",true))
                // Preserve completed observations if a later run fails; never overwrite timings.json.
                File(outputs,"retention-diagnostic.json").writeText(report.toString(2))
            }
            DngContentVerifier.verify(source.fingerprint,{input.inputStream()})
            success=true
        } finally {
            instrumentation.runOnMainSync { fixture.content=null }
            instrumentation.waitForIdleSync()
            GpuPhotoRollRetiredOwners.releaseBeforeOpen()
            if(success) { assertTrue(input.delete()); assertTrue(root.delete()) }
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
    private fun run(source: SharedOriginalReference, geometry: Original24PreflightGeometry,
        input: File, scratch: File, fallback: Boolean, capture: Boolean, output: File,
        verifyParameterNodes: Boolean = true): Times {
        viewportLayout.set(null)
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
        var guideViewport: Rect?=null
        val saveCalls=AtomicInteger()
        val evidence=JSONObject().put("scope","View boundary and real GPU, not full Editor/save-owner E2E")
            .put("parameterNodeAssertions",verifyParameterNodes)
            .put("purpose",if(verifyParameterNodes) "strict UI validation" else "visual artifacts only; not strict UI pass or performance evidence")
            .put("settings","same source; camera WB linear AS_SHOT; half clamp+sRGB vs FULL GPU before; exact uses unchanged defaults")
        val valueBounds=mutableListOf<Rect>()
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
                    {saveCalls.incrementAndGet()},{},{},{},{_,_,_->},{},{},{},{},
                    modifier=Modifier.fillMaxSize().safeDrawingPadding(),
                    viewportModifier=Modifier.onGloballyPositioned { layout ->
                        val origin=layout.positionOnScreen()
                        viewportLayout.set(Rect(origin.x.roundToInt(),origin.y.roundToInt(),
                            origin.x.roundToInt()+layout.size.width,origin.y.roundToInt()+layout.size.height))
                    }.drawWithContent {
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
                            // A Compose draw record precedes accessibility publication; require both,
                            // just as the product Surface fixture does, without relaxing the label gate.
                            await { times.composeRecord.get()!=0L &&
                                find("RAW基準の案内画像 · 調整は未反映")!=null }
                            assertNotNull(find("RAW基準の案内画像 · 調整は未反映"))
                            assertFalse("comparison disabled before exact",hasComparisonAction())
                            assertNoGuideValueWarnings()
                            val save=actionNode(checkNotNull(find("JPEGを保存")))
                            assertFalse("guide save control disabled",save.isEnabled)
                            val saveBounds=Rect().also { save.getBoundsInScreen(it) }
                            tap(saveBounds)
                            assertEquals("View disabled tap never invokes callback",0,saveCalls.get())
                            guideCapture=screen(File(output,"guide.png"))
                            recordNodeKinds(File(output,"guide-nodes.local.json"))
                            guideViewport=imageBounds()
                            valueBounds.clear()
                            if(verifyParameterNodes) valueBounds.addAll(parameterButtonBounds(enabled = false))
                            if(verifyParameterNodes) assertBlackLetterbox(checkNotNull(guideCapture),checkNotNull(guideViewport))
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
                    assertNull(find("RAW基準の案内画像 · 調整は未反映"))
                    val exactCapture=screen(File(output,"exact.png"))
                    try {
                        assertFalse("guide and exact display differ",requireNotNull(guideCapture).sameAs(exactCapture))
                        assertNotNull(find(developCaption(false,false).body))
                        val viewport=imageBounds()
                        if(verifyParameterNodes) assertEquals("same image viewport",guideViewport,viewport)
                        if(verifyParameterNodes) assertEquals("three parameter button positions/sizes unchanged",valueBounds,
                            parameterButtonBounds(enabled = true))
                        if(verifyParameterNodes) assertBlackLetterbox(exactCapture,viewport)
                        val draws=drawCount(owner)
                        val down=SystemClock.uptimeMillis()
                        touch(MotionEvent.ACTION_DOWN,viewport,down)
                        try {
                            SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong()+100)
                            await { find(developCaption(true,false).body)!=null && drawCount(owner)>draws }
                            val baseline=screen(File(output,"baseline.png"))
                            try {
                                val baselineViewport=imageBounds()
                                if(verifyParameterNodes) {
                                    assertEquals(viewport,baselineViewport)
                                    assertBlackLetterbox(baseline,viewport)
                                }
                                // All three PNGs exist before artifact-only spatial checks. Record any
                                // mismatch rather than misreporting this capture as a strict UI pass.
                                evidence.put("sameMeasuredViewport",guideViewport==viewport && viewport==baselineViewport)
                                    .put("guideLetterboxBlack",letterboxBlack(requireNotNull(guideCapture),checkNotNull(guideViewport)))
                                    .put("exactLetterboxBlack",letterboxBlack(exactCapture,viewport))
                                    .put("baselineLetterboxBlack",letterboxBlack(baseline,baselineViewport))
                                    .put("viewportMeaning","mixed layout/semantic observation, not a rendered-pixel bounds proof; excludes overlay-clipped accessibility bottom")
                                    .put("guideMeasuredViewport",rectJson(checkNotNull(guideViewport)))
                                    .put("exactMeasuredViewport",rectJson(viewport))
                                    .put("baselineMeasuredViewport",rectJson(baselineViewport))
                                evidence.put("viewport",JSONArray(listOf(viewport.left,viewport.top,viewport.right,viewport.bottom)))
                                    .put("guideVsBaseline",pixelDifference(requireNotNull(guideCapture),baseline,viewport))
                                    .put("exactVsBaseline",pixelDifference(exactCapture,baseline,viewport))
                                    .put("valueRegionPeakRgb",JSONArray(valueBounds.map { bounds -> JSONObject()
                                        .put("guide",peakRgb(requireNotNull(guideCapture),bounds))
                                        .put("exact",peakRgb(exactCapture,bounds)) }))
                                    .put("valueDimmingMeaning","observed button-region maxima including labels, not a text-color proof")
                                    .put("saveCallbackCount",saveCalls.get())
                                File(output,"visual-evidence.json").writeText(evidence.toString(2))
                            } finally { baseline.recycle() }
                        } finally { touch(MotionEvent.ACTION_UP,viewport,down) }
                        await { find(developCaption(false,false).body)!=null }
                        assertEquals(values,owner.presented.value)
                    } finally { exactCapture.recycle() }
                    // Real surface lifecycle, not a source/editor retry simulation.
                    scenario.moveToState(Lifecycle.State.CREATED)
                    await { !owner.renderer.surfaceAttached.value }
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    await { owner.renderer.surfaceAttached.value && owner.presented.value==values }
                    assertNull(find("RAW基準の案内画像 · 調整は未反映"))
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
    private fun recordNodeKinds(file: File) {
        val records=JSONArray()
        val known=setOf("強さ","色温度","明るさ","50","+0","+0.0")
        fun walk(node: AccessibilityNodeInfo?) {
            if(node==null) return
            val bounds=Rect().also { node.getBoundsInScreen(it) }
            records.put(JSONObject().put("class",node.className?.toString())
                .put("label",node.text?.toString()?.takeIf { it in known } ?: "other")
                .put("enabled",node.isEnabled).put("clickable",node.isClickable)
                .put("bounds",JSONArray(listOf(bounds.left,bounds.top,bounds.right,bounds.bottom))))
            for(i in 0 until node.childCount) walk(node.getChild(i))
        }
        fixture.automation.clearCache(); walk(fixture.fixtureRoot())
        file.writeText(records.toString(2))
    }
    private fun parameterButtonBounds(enabled: Boolean): List<Rect> {
        fixture.automation.clearCache()
        val root=checkNotNull(fixture.fixtureRoot())
        fun texts(node: AccessibilityNodeInfo): String = buildString {
            append(node.text?.toString().orEmpty()); append("\n")
            for(i in 0 until node.childCount) node.getChild(i)?.let { append(texts(it)) }
        }
        val labels=listOf("強さ" to "50", "色温度" to "+0", "明るさ" to "+0.0")
        val bounds=labels.map { (label,value) ->
            val matches=mutableListOf<AccessibilityNodeInfo>()
            fun walk(node: AccessibilityNodeInfo) {
                // Match the visible key label, then its clickable ancestor, as DevelopViewAndroidTest
                // does. Compose selected keys need not map to android.widget.Button.
                if(node.text?.toString()==label) matches.add(actionNode(node))
                for(i in 0 until node.childCount) node.getChild(i)?.let(::walk)
            }
            walk(root)
            assertEquals("one parameter button: $label",1,matches.size)
            val node=matches.single()
            val tokens=texts(node).split(Regex("\\s+")).filter { it.isNotBlank() }
            assertTrue("parameter value: $label",value in tokens)
            assertEquals("parameter enabled state: $label",enabled,node.isEnabled)
            Rect().also { node.getBoundsInScreen(it); assertFalse(it.isEmpty) }
        }
        assertEquals(3,bounds.distinct().size)
        return bounds
    }
    private fun imageBounds(): Rect {
        val visible=Rect().also { checkNotNull(find("現像結果（表示用）")).getBoundsInScreen(it) }
        val layout=checkNotNull(viewportLayout.get())
        // Accessibility reports the visible region clipped by the loading/status overlay.
        // Its top excludes the caption; the actual Compose layout supplies the unclipped bottom.
        check(visible.top>=layout.top && visible.top<layout.bottom)
        return Rect(layout.left,visible.top,layout.right,layout.bottom)
    }
    private fun assertNoGuideValueWarnings() {
        fixture.automation.clearCache()
        fun walk(node: AccessibilityNodeInfo?) {
            if(node==null) return
            val text=node.text?.toString().orEmpty()
            assertFalse("guide omits unreflected value text",text.contains("指定") && text.contains("未反映"))
            assertFalse("guide omits no displayed value text",text.contains("表示中の値なし"))
            for(i in 0 until node.childCount) walk(node.getChild(i))
        }
        walk(fixture.fixtureRoot())
    }
    private fun touch(action: Int,rect: Rect,down: Long) {
        val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,rect.exactCenterX(),rect.exactCenterY(),0)
        try { assertTrue(fixture.automation.injectInputEvent(event,true)) } finally { event.recycle() }
    }
    private fun tap(rect: Rect) {
        val down=SystemClock.uptimeMillis(); touch(MotionEvent.ACTION_DOWN,rect,down)
        touch(MotionEvent.ACTION_UP,rect,down); instrumentation.waitForIdleSync()
    }
    private fun drawCount(owner: GpuPhotoRollSession): Long {
        val queue=LinkedBlockingQueue<GpuPreviewSnapshot>()
        owner.renderer.snapshot { queue.offer(it) }
        return checkNotNull(queue.poll(10,TimeUnit.SECONDS)).drawCount
    }
    private fun assertBlackLetterbox(bitmap: Bitmap,rect: Rect) {
        // Portrait fixture, 3:2 landscape input: both points are in image letterbox, not caption/UI.
        for(y in listOf(rect.top+rect.height()/8,rect.bottom-rect.height()/8))
            assertEquals("image letterbox is pure black",Color.BLACK,bitmap.getPixel(rect.centerX(),y))
    }
    private fun rectJson(rect: Rect)=JSONArray(listOf(rect.left,rect.top,rect.right,rect.bottom))
    private fun letterboxBlack(bitmap: Bitmap,rect: Rect): Boolean =
        listOf(rect.top+rect.height()/8,rect.bottom-rect.height()/8).all { y ->
            bitmap.getPixel(rect.centerX(),y)==Color.BLACK
        }
    private fun peakRgb(bitmap: Bitmap,rect: Rect): Int {
        var peak=0
        for(y in rect.top.coerceAtLeast(0) until rect.bottom.coerceAtMost(bitmap.height))
            for(x in rect.left.coerceAtLeast(0) until rect.right.coerceAtMost(bitmap.width)) {
                val p=bitmap.getPixel(x,y); peak=maxOf(peak,Color.red(p),Color.green(p),Color.blue(p))
            }
        return peak
    }
    private fun pixelDifference(a: Bitmap,b: Bitmap,rect: Rect): JSONObject {
        // Crop mathematically to centered 3:2 Fit image; never edit the captured PNGs.
        val height=minOf(rect.height(),rect.width()*2/3)
        val top=rect.centerY()-height/2
        var total=0L; var changed=0L; var maximum=0
        for(y in top until top+height) for(x in rect.left until rect.right) {
            val p=a.getPixel(x,y); val q=b.getPixel(x,y)
            val d=intArrayOf(kotlin.math.abs(Color.red(p)-Color.red(q)),
                kotlin.math.abs(Color.green(p)-Color.green(q)),kotlin.math.abs(Color.blue(p)-Color.blue(q)))
            if(d.any { it!=0 }) changed++
            total+=d.sum(); maximum=maxOf(maximum,d.max())
        }
        return JSONObject().put("changedPixels",changed).put("maxChannelDelta",maximum)
            .put("meanChannelDelta",total.toDouble()/(rect.width().toLong()*height*3))
            .put("meaning","screen-space same-position comparison, no equality threshold; resampling/demosaic can differ")
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
