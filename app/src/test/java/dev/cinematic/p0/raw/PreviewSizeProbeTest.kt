package dev.cinematic.p0.raw

import org.junit.Test
import org.junit.Assert.*
import kotlin.math.*

/** Synthetic host observation only; never a device performance assertion. */
class PreviewSizeProbeTest {
    private fun input(w: Int, h: Int, detail: Boolean): LinearSrgbImage = LinearSrgbImage(w,h,FloatArray(w*h*3) { i ->
        val x=(i/3)%w; val y=(i/3)/w
        val u=(x+.5)/w; val v=(y+.5)/h
        val light=exp(-((u-.4).pow(2)+(v-.45).pow(2))/.003)*1.6
        (0.04+u*.45+light+(if(detail && (x/2+y/2)%2==0) .3 else 0.0)+(i%3)*.015).toFloat()
    })
    private fun resize(a: LinearSrgbImage,w: Int,h: Int): LinearSrgbImage {
        val out=FloatArray(w*h*3)
        repeat(3) { c ->
            val p=StageClearBlur.resize(FloatPlane(a.width,a.height,FloatArray(a.pixelCount){a[it*3+c]}),w,h,StageClearBlur.Filter.BOX)
            repeat(w*h) { out[it*3+c]=p[it] }
        }
        return LinearSrgbImage(w,h,out)
    }
    @Test fun observeSizes() {
        for(detail in listOf(false,true)) {
            val large=input(1024,768,detail); val small=resize(large,256,192)
            val prepared=listOf(small,large).associateWith { a ->
                val t=System.nanoTime(); val p=PreparedStageClearGlow.prepare(a){}
                println("PROBE prepare size=${a.width} detail=$detail ms=${(System.nanoTime()-t)/1e6}");p
            }
            val outputs=mutableMapOf<Int,LinearSrgbImage>()
            for(w in listOf(256,1024,1024,256,256,1024,1024,256)) {
                val a=if(w==256) small else large
                val t=System.nanoTime();val r=prepared.getValue(a).render(FaceMask(a.width,a.height,FloatArray(a.pixelCount)),.5){}
                println("PROBE render size=$w detail=$detail ms=${(System.nanoTime()-t)/1e6} face=${r.faceCutFactor}")
                outputs[w]=r.image
            }
            val ref=resize(outputs.getValue(1024),256,192); val low=outputs.getValue(256)
            var sum=0.0;var max=0.0
            repeat(low.pixelCount*3){val d=abs(low[it]-ref[it]).toDouble();sum+=d;max=maxOf(max,d)}
            println("PROBE difference detail=$detail mae=${sum/(low.pixelCount*3)} max=$max auto256=${StageClearMist.autoStrength(small)} auto1024=${StageClearMist.autoStrength(large)}")
            for(r in listOf(.006,.01,.012,.02,.03,.06)) {
                val s=StageClearBlur.reducedSize(256,192,r);val l=StageClearBlur.reducedSize(1024,768,r)
                println("PROBE grid radius=$r small=$s large=$l"); assertEquals(l,s)
            }
            assertTrue(sum.isFinite())
        }
    }
    @Test fun observeBlurAndAutomaticDecision() {
        for(r in listOf(.006,.01,.012,.02,.025,.03,.06,.07)) {
            val profiles=mutableMapOf<Int,FloatPlane>()
            for(w in listOf(256,1024)) {
                val h=w*3/4
                val a=FloatPlane(w,h,FloatArray(w*h){if(it%w < w/2) 0f else 1f})
                val b=StageClearBlur.plane(a,r);profiles[w]=b
                fun crossing(level:Float):Double {
                    val y=h/2; val hi=(1 until w).first{b[y*w+it]>=level};val lo=hi-1
                    return lo+(level-b[y*w+lo])/(b[y*w+hi]-b[y*w+lo]).toDouble()
                }
                println("BLUR r=$r size=$w grid=${StageClearBlur.reducedSize(w,h,r)} edgeWidth=${crossing(.9f)-crossing(.1f)} normalized=${(crossing(.9f)-crossing(.1f))/w}")
            }
            val b=StageClearBlur.resize(profiles.getValue(1024),256,192,StageClearBlur.Filter.BOX)
            val a=profiles.getValue(256);var sum=0.0;var peak=0.0
            repeat(256*192){val d=abs(a[it]-b[it]).toDouble();sum+=d;peak=maxOf(peak,d)}
            println("BLUR difference r=$r mae=${sum/(256*192)} max=$peak")
        }
        for(detail in listOf(false,true)) {
            val large=input(1024,768,detail);val small=resize(large,256,192)
            for(a in listOf(small,large)) {
                val glow=StageClearGlow.render(a,.5)
                println("AUTO actualGlow detail=$detail size=${a.width} strength=${StageClearMist.autoStrength(glow)} nearWhite=${StageClearMist.nearWhiteFraction(glow)}")
            }
        }
    }
}
