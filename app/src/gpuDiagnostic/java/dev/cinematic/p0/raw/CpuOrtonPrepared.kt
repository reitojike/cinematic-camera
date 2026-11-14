package dev.cinematic.p0.raw

import dev.cinematic.p0.gpu.constants.StageClearOrtonV1
import dev.cinematic.p0.gpu.constants.StageClearV1
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** GPU合成と同じ準備済みfieldを受ける診断比較専用。製品Ortonの演算順序を維持。 */
internal object CpuOrtonPrepared {
    fun render(image:LinearSrgbImage,soft:LinearSrgbImage,skin:FloatPlane,amount:Double):LinearSrgbImage {
        val encoded=FloatArray(image.pixelCount*3){StageClearColor.linearToUnit(image[it])}
        val bright=FloatArray(encoded.size){i->val inverse=1f-encoded[i];1f-inverse*inverse}
        val mixed=FloatArray(encoded.size)
        repeat(image.pixelCount){p->
            val i=p*3;val luma=StageClearV1.LUMA
            val y=encoded[i]*luma[0]+encoded[i+1]*luma[1]+encoded[i+2]*luma[2]
            val fade=StageClearOrton.highlightFade(y)
            repeat(3){c->val j=i+c
                val display=encoded[j]+StageClearOrtonV1.AMOUNT.toFloat()*fade*(bright[j]*soft[j].coerceIn(0f,1f)-encoded[j])
                val value=StageClearColor.unitToLinear(display)
                mixed[j]=if(image[j]>1f)max(image[j],value)else value
            }
        }
        val candidate=LinearSrgbImage.takeOwnership(image.width,image.height,mixed)
        val orton=FloatArray(encoded.size);val before=FloatArray(3);val after=FloatArray(3)
        repeat(image.pixelCount){p->
            StageClearColor.toLab(image,p*3,before);StageClearColor.toLab(candidate,p*3,after)
            val c0=hypot(before[1],before[2]);val c1=hypot(after[1],after[2]);val keep=skin[p]
            val scale=(1f-keep)+keep*min(1f,c0/max(c1,StageClearOrtonV1.CHROMA_FLOOR.toFloat()))
            after[1]*=scale;after[2]*=scale;StageClearColor.toRgb(after,orton,p*3)
        }
        val output=FloatArray(encoded.size){i->image[i]+amount.toFloat()*(orton[i]-image[i])}
        return LinearSrgbImage.takeOwnership(image.width,image.height,output)
    }
}