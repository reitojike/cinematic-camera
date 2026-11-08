package dev.cinematic.p0.raw

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Debug
import android.os.IBinder
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** 明示的なタップでだけ合成診断を開始。起動や再生成だけでは処理しない。 */
class GpuDiagnosticActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        panel.addView(TextView(this).apply { text = "GPU合成診断・製品未採用\n写真は読みません。結果は診断ログへ記録します。背景検証は開始後にホームへ戻ってください。" })
        for ((label, mode) in listOf("小型の正しさ確認" to "small", "1024px 全Look比較" to "preview", "12MP ぼかし単体" to "blur12", "12MP Orton単体" to "orton12")) {
            panel.addView(Button(this).apply {
                text = label
                setOnClickListener { startForegroundService(Intent(this@GpuDiagnosticActivity, GpuDiagnosticService::class.java).setAction(mode)) }
            })
        }
        panel.addView(Button(this).apply { text = "取り消す"; setOnClickListener { GpuDiagnosticService.cancel() } })
        setContentView(panel)
    }
}

/** 製品保存と同じAPI別FGS種別だが、保存・復旧の試験ではない。 */
class GpuDiagnosticService : Service() {
    private var worker: Thread? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = intent?.action
        if (mode !in setOf("small", "preview", "blur12", "orton12") || worker != null) {
            if (worker == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        stop.set(false)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("gpu-probe", "GPU合成診断", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, "gpu-probe").setSmallIcon(dev.cinematic.p0.R.drawable.ic_probe)
            .setContentTitle("GPU合成診断を実行中").setContentText("製品の写真や保存先には触れません").build()
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        startForeground(237, notification, type)
        val nextWorker = Thread({
            val sampling = AtomicBoolean(true)
            var managedMax = 0L; var nativeMax = 0L
            val sampler = Thread({
                while (sampling.get()) {
                    managedMax = maxOf(managedMax, Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                    nativeMax = maxOf(nativeMax, Debug.getNativeHeapAllocatedSize())
                    try { Thread.sleep(100) } catch (_: InterruptedException) { break }
                }
            }, "gpu-probe-memory").apply { start() }
            try {
                environment("start")
                when (mode) {
                    "blur12" -> runBlur12()
                    "orton12" -> runOrton12()
                    else -> compare(if (mode == "small") 257 else 1024)
                }
                log("完了 mode=$mode")
            } catch (_: CancellationException) { log("取消 mode=$mode") }
            catch (failure: Throwable) { log("失敗 mode=$mode type=${failure.javaClass.simpleName}") }
            finally {
                environment("end")
                sampling.set(false); sampler.interrupt(); sampler.join()
                log("観測最大 managedBytes=$managedMax nativeBytes=$nativeMax intervalMs=100 PSSは別計測")
                worker = null; stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(startId)
            }
        }, "gpu-probe-owner")
        worker = nextWorker
        nextWorker.start()
        return START_NOT_STICKY
    }
    override fun onDestroy() { stop.set(true); super.onDestroy() }
    private fun environment(phase:String) {
        val power=getSystemService(android.os.PowerManager::class.java)
        val battery=registerReceiver(null,android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        log("条件 phase=$phase thermal=${power.currentThermalStatus} interactive=${power.isInteractive} batteryDeciC=${battery?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE,-1)} plugged=${battery?.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED,-1)}")
    }
    private fun checkpoint() { if (stop.get()) throw CancellationException() }
    private fun sample(x: Int, y: Int, width: Int, height: Int, channel: Int, dark: Boolean): Float {
        val u = x.toFloat() / (width - 1); val v = y.toFloat() / (height - 1)
        if (dark) return u * u * .018f
        return when(channel) { 0 -> u * 1.6f - .02f; 1 -> v * .7f; else -> if (u > .8 && v > .7) 2f else (1-u)*.3f }
    }
    private fun compare(width: Int) {
        val height = width * 3 / 4 + 1
        for (dark in listOf(false, true)) {
            checkpoint()
            val input = LinearSrgbImage(width, height, FloatArray(width * height * 3) { i -> sample((i/3)%width,(i/3)/width,width,height,i%3,dark) })
            val base = R7Base.render(input, FloatPlane(width,height,FloatArray(width*height)),0.0)
            val skin = StageClearGlow.skinWeight(base)
            val faces = FaceMask(width,height,FloatArray(width*height))
            for (strength in listOf(.25,.5,1.0)) {
                checkpoint()
                val beforeCpu = System.nanoTime()
                val cpu = StageClearLook.renderFromBase(base,faces,strength).image
                val cpuNs = System.nanoTime()-beforeCpu
                val beforeGpu = System.nanoTime()
                val glow = GpuDiagnosticGlow.render(base,strength,skin)
                val blurNs=GpuProbeImages.lastWallNs.get() ?: 0L
                var ortonNs=0L; var ortonPreparationNs=0L
                val gpu = StageClearProtections.finish(glow,faces) {
                    val mist = StageClearMist.render(it,skin,strength)
                    val observation=GpuDiagnosticOrton.render(mist,skin,64) { stop.get() }
                    ortonNs+=observation.gpuWallNs;ortonPreparationNs+=observation.cpuPrepareNs
                    StageClearCharacter.render(observation.output,strength)
                }.image
                val gpuNs = System.nanoTime()-beforeGpu
                log("対象GPU2工程 blurNs=$blurNs ortonNs=$ortonNs sumNs=${blurNs+ortonNs} ortonCpuPreparationNs=$ortonPreparationNs EGL初期化と転送同期readback込み")
                var max = 0; var changed = 0; var flatEdges = 0
                for (p in 0 until cpu.pixelCount) {
                    var delta = 0
                    repeat(3) { c -> delta = maxOf(delta,abs(quantize(cpu[p*3+c])-quantize(gpu[p*3+c]))) }
                    max = maxOf(max,delta); if(delta>0) changed++
                    if (dark && p%width>0 && quantize(cpu[p*3])!=quantize(cpu[(p-1)*3]) && quantize(gpu[p*3])==quantize(gpu[(p-1)*3])) flatEdges++
                }
                log("全Look width=$width dark=$dark strength=$strength cpuNs=$cpuNs gpuNs=$gpuNs max8=$max changed=$changed pixels=${cpu.pixelCount} newFlatEdges=$flatEdges")
                check(max<=2) { "プレビュー許容差を超えました" }
            }
        }
    }
    private fun runBlur12() {
        val width=4000; val height=3000
        val source:(Int,Int)->FloatArray={top,rows->FloatArray(width*rows*4){i->if(i%4==3)1f else sample((i/4)%width,top+(i/4)/width,width,height,i%4,false)}}
        var cpuChecksum=0.0;var gpuChecksum=0.0
        val cpuNs=CpuBlurProbe.run(width,height,StageClearGlowV1.BLOOM_KERNELS.first().first,source=source,
            consume={_,frame->for(i in frame.rgba.indices step 4)cpuChecksum+=frame.rgba[i]},cancelled={stop.get()})
        val result=GpuBlurProbe.run(width,height,StageClearGlowV1.BLOOM_KERNELS.first().first,source=source,
            consume={_,frame->for(i in frame.rgba.indices step 4)gpuChecksum+=frame.rgba[i]},cancelled={stop.get()})
        log("12MPぼかし単体 cpuNs=$cpuNs gpuNs=${result.elapsedNs} uploadNs=${result.uploadNs} drawNs=${result.drawNs} readbackNs=${result.readbackNs} passBytes=${result.largestPassBytes} renderer=${result.renderer} finite=${cpuChecksum.isFinite()&&gpuChecksum.isFinite()} 順序CPU→GPU・全保存経路ではない")
    }
    /** 12MP負荷・一定色の品質試験。全Look/JPEGではない。文脈生成は各256行に含む。 */
    private fun runOrton12() {
        val width=4000; val height=3000; val source=floatArrayOf(.2f,.3f,.4f)
        var totalNs=0L; var cpuTotalNs=0L; var max=0; var changed=0; var contexts=0
        for(top in 0 until height step 256){
            checkpoint()
            val rows=minOf(256,height-top)
            val image=LinearSrgbImage(width,rows,FloatArray(width*rows*3){source[it%3]})
            val skin=FloatPlane(width,rows,FloatArray(width*rows){.3f})
            val soft=LinearSrgbImage(width,rows,FloatArray(width*rows*3){
                val inverse=1f-StageClearColor.linearToUnit(source[it%3]);1f-inverse*inverse
            })
            val started=System.nanoTime()
            val gpu=GpuDiagnosticOrton.prepared(image,soft,skin,.5,64){stop.get()}.output
            totalNs+=System.nanoTime()-started;contexts++
            // 一定色なので全画像ぼかしと帯内ぼかしが一致する。CPU比較はGPU時間の外。
            val cpuStarted=System.nanoTime()
            val cpu=CpuOrtonPrepared.render(image,soft,skin,.5)
            cpuTotalNs+=System.nanoTime()-cpuStarted
            for(p in 0 until image.pixelCount){
                var delta=0;repeat(3){c->delta=maxOf(delta,abs(quantize(cpu[p*3+c])-quantize(gpu[p*3+c])))}
                max=maxOf(max,delta);if(delta>0)changed++
            }
            check(max<=1){"Orton単体の保存候補許容差を超えました"}
        }
        log("12MPOrton単体 gpuNs=$totalNs cpuPreparedNs=$cpuTotalNs contexts=$contexts max8=$max changed=$changed pixels=${width*height} 一定色・全保存経路ではない")
    }
    private fun quantize(value:Float):Int {
        val v=value.coerceIn(0f,1f).toDouble()
        return ((if(v<=.0031308)v*12.92 else 1.055*v.pow(1/2.4)-.055)*255).roundToInt().coerceIn(0,255)
    }
    companion object {
        private val stop=AtomicBoolean(false)
        fun cancel(){stop.set(true)}
        fun cancelled()=stop.get()
        private fun log(value:String){android.util.Log.i("GpuDiagnostic",value)}
    }
}