package dev.cinematic.p0.raw

import java.util.concurrent.CancellationException

/** 診断の同条件比較用。製品BOX/BILINEARの係数と軸ごとのfloat丸めを保つ。 */
internal object CpuBlurProbe {
    fun run(width:Int,height:Int,radius:Double,band:Int=64,source:(Int,Int)->FloatArray,
            consume:(Int,GpuBlurProbe.Frame)->Unit,cancelled:()->Boolean={false}):Long {
        val started=System.nanoTime()
        val (sw,sh)=StageClearBlur.reducedSize(width,height,radius)
        require(height-sh<=2*(width-sw))
        fun checkpoint(){if(cancelled())throw CancellationException()}
        fun horizontal(input:GpuBlurProbe.Frame,outWidth:Int,filter:StageClearBlur.Filter):GpuBlurProbe.Frame {
            val samples=StageClearBlur.samples(input.width,outWidth,filter)
            val out=FloatArray(outWidth*input.height*4)
            for(y in 0 until input.height){checkpoint();for(x in 0 until outWidth){
                val sample=samples[x]
                repeat(3){c->var sum=0.0;for(k in sample.weights.indices)sum+=input.rgba[(y*input.width+sample.first+k)*4+c]*sample.weights[k];out[(y*outWidth+x)*4+c]=sum.toFloat()}
                out[(y*outWidth+x)*4+3]=1f
            }}
            return GpuBlurProbe.Frame(outWidth,input.height,out)
        }
        fun vertical(input:GpuBlurProbe.Frame,outHeight:Int,filter:StageClearBlur.Filter,top:Int=0,rows:Int=outHeight):GpuBlurProbe.Frame {
            val samples=StageClearBlur.samples(input.height,outHeight,filter)
            val out=FloatArray(input.width*rows*4)
            for(y in 0 until rows){checkpoint();val sample=samples[top+y];for(x in 0 until input.width){
                repeat(3){c->var sum=0.0;for(k in sample.weights.indices)sum+=input.rgba[((sample.first+k)*input.width+x)*4+c]*sample.weights[k];out[(y*input.width+x)*4+c]=sum.toFloat()}
                out[(y*input.width+x)*4+3]=1f
            }}
            return GpuBlurProbe.Frame(input.width,rows,out)
        }
        val intermediate=FloatArray(sw*height*4)
        for(top in 0 until height step band){
            checkpoint();val rows=minOf(band,height-top)
            val part=horizontal(GpuBlurProbe.Frame(width,rows,source(top,rows)),sw,StageClearBlur.Filter.BOX)
            part.rgba.copyInto(intermediate,top*sw*4)
        }
        val small=vertical(GpuBlurProbe.Frame(sw,height,intermediate),sh,StageClearBlur.Filter.BOX)
        val wide=horizontal(small,width,StageClearBlur.Filter.BILINEAR)
        for(top in 0 until height step band){checkpoint();consume(top,vertical(wide,height,StageClearBlur.Filter.BILINEAR,top,minOf(band,height-top)))}
        checkpoint();return System.nanoTime()-started
    }
}