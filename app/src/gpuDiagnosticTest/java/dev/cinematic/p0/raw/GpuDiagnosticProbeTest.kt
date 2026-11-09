package dev.cinematic.p0.raw

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CancellationException
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** 通常CIではsource自体を含めず、専用APKでも明示引数なしではGPUを触らない。 */
class GpuDiagnosticProbeTest {
    @Before fun optIn() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("gpuPixelProbe") == "true")
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
    }
    @Test fun integratedEntrypointsDoNotStartGpu() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        for (target in listOf(DiagnosticHubActivity::class.java, GpuDiagnosticActivity::class.java,
            dev.cinematic.p0.rawdiagnostic.RawDiagnosticActivity::class.java,
            dev.cinematic.p0.photoroll.PhotoRollActivity::class.java)) {
            val activity = instrumentation.startActivitySync(android.content.Intent(context, target)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            try {
                instrumentation.waitForIdleSync()
                assertFalse("入口の表示だけではGPUを開始しない", GpuDiagnosticService.active())
                assertFalse("RAW診断も選択なしでは開始しない", dev.cinematic.p0.rawdiagnostic.RawDiagnosticActivity.activeOrHeld())
            } finally { instrumentation.runOnMainSync { activity.finish() }; instrumentation.waitForIdleSync() }
        }
    }
    @Test fun repeatedStartsCancelAndStopService() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val activity=instrumentation.startActivitySync(android.content.Intent(context,GpuDiagnosticActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        fun await(label:String,predicate:()->Boolean){
            val deadline=android.os.SystemClock.elapsedRealtime()+15000
            while(!predicate() && android.os.SystemClock.elapsedRealtime()<deadline)Thread.sleep(50)
            assertTrue(label,predicate())
        }
        try {
            repeat(2){
                instrumentation.runOnMainSync { repeat(3){context.startForegroundService(android.content.Intent(context,GpuDiagnosticService::class.java).setAction("small"))} }
                await("処理開始"){GpuDiagnosticService.active()}
                GpuDiagnosticService.cancel()
                await("GPUと計測スレッドの終了"){!GpuDiagnosticService.active()}
                val manager=context.getSystemService(android.app.ActivityManager::class.java)
                @Suppress("DEPRECATION")
                fun running()=manager.getRunningServices(100).any{it.service.className==GpuDiagnosticService::class.java.name}
                await("連打後もFGSが終了"){!running()}
            }
        }finally{GpuDiagnosticService.cancel();instrumentation.runOnMainSync{activity.finish()}}
    }
    @Test fun blurMatchesReferenceAndBandBoundaries() {
        val width=257;val height=193
        val input=LinearSrgbImage(width,height,FloatArray(width*height*3){i->((i/3)%width).toFloat()/(width-1)*1.6f-.02f})
        val radius=StageClearGlowV1.BLOOM_KERNELS.first().first
        val cpu=StageClearBlur.rgb(input,radius)
        fun render(band:Int):FloatArray {
            val out=FloatArray(width*height*3)
            GpuBlurProbe.run(width,height,radius,band,source={top,rows->FloatArray(width*rows*4){i->if(i%4==3)1f else input[(top*width+i/4)*3+i%4]}},
                consume={top,frame->repeat(width*frame.height){p->repeat(3){c->out[(top*width+p)*3+c]=frame.rgba[p*4+c]}}})
            return out
        }
        val streamed=FloatArray(width*height*3)
        CpuBlurProbe.run(width,height,radius,31,source={top,rows->FloatArray(width*rows*4){i->if(i%4==3)1f else input[(top*width+i/4)*3+i%4]}},
            consume={top,frame->repeat(width*frame.height){p->repeat(3){c->streamed[(top*width+p)*3+c]=frame.rgba[p*4+c]}}})
        assertArrayEquals("CPU帯参照と既存処理は完全一致",cpu.copyPixels(),streamed,0f)
        val a=render(31);val b=render(64)
        assertArrayEquals(a,b,0f)
        for(i in a.indices)assertTrue("blur difference",abs(a[i]-cpu[i])<.00001f)
    }
    @Test fun preparedCpuMatchesOriginalOrton() {
        val w=257;val h=193
        val image=LinearSrgbImage(w,h,FloatArray(w*h*3){i->if(i%3==0).2f else .4f})
        val skin=FloatPlane(w,h,FloatArray(w*h){.3f})
        val bright=LinearSrgbImage(w,h,FloatArray(w*h*3){i->val inv=1f-StageClearColor.linearToUnit(image[i]);1f-inv*inv})
        val soft=StageClearBlur.rgb(bright,StageClearOrtonV1.RADIUS)
        val reference=StageClearOrton.render(StageClearMist.Result(image,.5),skin)
        val prepared=CpuOrtonPrepared.render(image,soft,skin,.5)
        assertArrayEquals(reference.copyPixels(),prepared.copyPixels(),0f)
    }
    @Test fun cancellationAndNextContext() {
        var checks=0
        try {
            GpuBlurProbe.run(65,49,.006,8,source={_,rows->FloatArray(65*rows*4){.2f}},consume={_,_->},cancelled={++checks>4})
            fail("取消されるべき")
        }catch(_:CancellationException){}
        val next=GpuBlurProbe.run(65,49,.006,8,source={_,rows->FloatArray(65*rows*4){.2f}},consume={_,_->})
        assertTrue(next.renderer.isNotEmpty())
    }
    @Test fun combinedLookWithinPreviewTolerance() {
        val w=257;val h=193
        for(dark in listOf(false,true)) {
            val base=LinearSrgbImage(w,h,FloatArray(w*h*3){i->val x=((i/3)%w).toFloat()/(w-1);if(dark)x*x*.018f else if(i%3==0)x*1.6f-.02f else .3f})
            val skin=StageClearGlow.skinWeight(base);val faces=FaceMask(w,h,FloatArray(w*h))
            val strengths=listOf(.25,.5,1.0,.25)
            fun render(session:GpuDiagnosticSession,strength:Double):LinearSrgbImage {
                val glow=GpuDiagnosticGlow.render(session,base,strength,skin)
                return StageClearProtections.finish(glow,faces){
                    StageClearCharacter.render(GpuDiagnosticOrton.render(session,StageClearMist.render(it,skin,strength),skin).output,strength)
                }.image
            }
            // 毎回EGL/programを作り直す経路との同一GPU上のbit完全一致。
            val fresh=strengths.map { strength -> GpuDiagnosticSession().use { render(it,strength).copyPixels() } }
            GpuDiagnosticSession().use { session ->
                for((index,strength) in strengths.withIndex()) {
                    val cpu=StageClearLook.renderFromBase(base,faces,strength).image
                    val gpu=render(session,strength)
                    assertArrayEquals("fresh/reused GPU output",fresh[index],gpu.copyPixels(),0f)
                    var max=0;var changed=0
                    for(p in 0 until w*h){var d=0;repeat(3){c->d=maxOf(d,abs(q(cpu[p*3+c])-q(gpu[p*3+c])))};max=maxOf(max,d);if(d>0)changed++}
                    android.util.Log.i("GpuDiagnostic","test dark=$dark strength=$strength max8=$max changed=$changed pixels=${w*h}")
                    assertTrue("全Lookの許容差2段",max<=2)
                }
            }
        }
    }
    @Test fun sessionReusesProgramsAndRejectsWrongThreadAndClosedUse() {
        val session=GpuDiagnosticSession()
        val context=android.opengl.EGL14.eglGetCurrentContext()
        val blur=session.blurProgram; val orton=session.ortonProgram
        fun draw(): FloatArray {
            val output=FloatArray(65*49*4)
            GpuBlurProbe.run(session,65,49,.006,8,source={_,rows->FloatArray(65*rows*4){.2f}},
                consume={top,frame->frame.rgba.copyInto(output,top*65*4)})
            return output
        }
        try {
            assertTrue(session.preparationNs>0)
            val first=draw()
            repeat(3){
                assertArrayEquals(first,draw(),0f)
                assertEquals(context,android.opengl.EGL14.eglGetCurrentContext())
                assertEquals(blur,session.blurProgram);assertEquals(orton,session.ortonProgram)
            }
            val failure=java.util.concurrent.atomic.AtomicReference<Throwable>()
            val other=Thread { try { session.close() } catch(error:Throwable){failure.set(error)} }
            other.start();other.join()
            assertTrue(failure.get() is IllegalStateException)
            session.requireCurrent()
        }finally{session.close()}
        session.close()
        assertEquals(android.opengl.EGL14.EGL_NO_CONTEXT,android.opengl.EGL14.eglGetCurrentContext())
        try { draw();fail("closed session must reject use") }catch(_:IllegalStateException){}
        GpuDiagnosticSession().use { it.requireCurrent() }
    }

    @Test fun consumerFailureAndCancellationReleaseReusableSession() {
        for(cancel in listOf(false,true)){
            var checks=0
            try{
                GpuDiagnosticSession().use { session ->
                    GpuBlurProbe.run(session,65,49,.006,8,source={_,rows->FloatArray(65*rows*4){.2f}},
                        consume={_,_->error("synthetic consumer failure")},cancelled={cancel && ++checks>4})
                }
                fail("expected failure")
            }catch(_:CancellationException){assertTrue(cancel)}
            catch(_:IllegalStateException){assertFalse(cancel)}
            assertEquals(android.opengl.EGL14.EGL_NO_CONTEXT,android.opengl.EGL14.eglGetCurrentContext())
            GpuDiagnosticSession().use { it.requireCurrent() }
        }
    }
    private fun q(value:Float):Int {val x=value.coerceIn(0f,1f).toDouble();return ((if(x<=.0031308)x*12.92 else 1.055*x.pow(1/2.4)-.055)*255).roundToInt()}
}