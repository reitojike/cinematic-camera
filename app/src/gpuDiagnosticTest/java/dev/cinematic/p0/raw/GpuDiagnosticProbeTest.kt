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
            for(strength in listOf(.25,.5,1.0)) {
                val cpu=StageClearLook.renderFromBase(base,faces,strength).image
                val glow=GpuDiagnosticGlow.render(base,strength,skin)
                val gpu=StageClearProtections.finish(glow,faces){
                    StageClearCharacter.render(GpuDiagnosticOrton.render(StageClearMist.render(it,skin,strength),skin).output,strength)
                }.image
                var max=0;var changed=0
                for(p in 0 until w*h){var d=0;repeat(3){c->d=maxOf(d,abs(q(cpu[p*3+c])-q(gpu[p*3+c])))};max=maxOf(max,d);if(d>0)changed++}
                android.util.Log.i("GpuDiagnostic","test dark=$dark strength=$strength max8=$max changed=$changed pixels=${w*h}")
                assertTrue("全Lookの許容差2段",max<=2)
            }
        }
    }
    private fun q(value:Float):Int {val x=value.coerceIn(0f,1f).toDouble();return ((if(x<=.0031308)x*12.92 else 1.055*x.pow(1/2.4)-.055)*255).roundToInt()}
}