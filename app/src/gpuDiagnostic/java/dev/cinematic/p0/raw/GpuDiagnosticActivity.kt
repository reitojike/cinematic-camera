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
import android.widget.CheckBox
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
        val memory=CheckBox(this).apply { text="メモリ観測（速度比較とは分ける）" }
        panel.addView(memory)
        val reverse=CheckBox(this).apply { text="比較順を逆にする" }
        panel.addView(reverse)
        for ((label, mode) in listOf("小型の正しさ確認" to "small", "1024px 全Look比較" to "preview", "12MP ぼかし単体" to "blur12", "12MP Orton単体" to "orton12")) {
            panel.addView(Button(this).apply {
                text = label
                setOnClickListener { startForegroundService(Intent(this@GpuDiagnosticActivity, GpuDiagnosticService::class.java).setAction(mode).putExtra("observeMemory",memory.isChecked).putExtra("reverse",reverse.isChecked)) }
            })
        }
        panel.addView(Button(this).apply { text = "取り消す"; setOnClickListener { GpuDiagnosticService.cancel() } })
        setContentView(panel)
    }
}

/** 製品保存と同じAPI別FGS種別だが、保存・復旧の試験ではない。 */
class GpuDiagnosticService : Service() {
    @Volatile private var worker: Thread? = null
    private val stop = AtomicBoolean(false)
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private var latestStartId = 0
    private var reverse = false
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val mode = intent?.action
        if (mode !in setOf("small", "preview", "blur12", "orton12") || worker != null || dev.cinematic.p0.rawdiagnostic.RawDiagnosticActivity.activeOrHeld() || dev.cinematic.p0.photoroll.ForegroundExports.gate.active() != null) {
            if (worker == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (!busy.compareAndSet(false,true)) { stopSelfResult(startId); return START_NOT_STICKY }
        lastOutcome = "running"
        cancellation = stop
        val observeMemory=intent?.getBooleanExtra("observeMemory",false) ?: false
        reverse=intent?.getBooleanExtra("reverse",false) ?: false
        stop.set(false)
        try {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("gpu-probe", "GPU合成診断", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, "gpu-probe").setSmallIcon(dev.cinematic.p0.R.drawable.ic_probe)
            .setContentTitle("GPU合成診断を実行中").setContentText("製品の写真や保存先には触れません").build()
        val type = if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        startForeground(237, notification, type)
        } catch (failure: Throwable) {
            lastOutcome = "failed"; cancellation = null; busy.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE)
            log("FGS開始失敗 type=${failure.javaClass.simpleName}")
            stopSelfResult(latestStartId)
            return START_NOT_STICKY
        }
        try {
        val nextWorker = Thread({
            val sampling = AtomicBoolean(true)
            var managedMax = 0L; var nativeMax = 0L
            var sampler:Thread? = null
            try {
            sampler = if (observeMemory) Thread({
                while (sampling.get()) {
                    managedMax = maxOf(managedMax, Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())
                    nativeMax = maxOf(nativeMax, Debug.getNativeHeapAllocatedSize())
                    try { Thread.sleep(100) } catch (_: InterruptedException) { break }
                }
            }, "gpu-probe-memory") else null
                sampler?.start()
                environment("start")
                when (mode) {
                    "blur12" -> runBlur12()
                    "orton12" -> runOrton12()
                    else -> compare(if (mode == "small") 257 else 1024)
                }
                lastOutcome = "completed"
                log("完了 mode=$mode")
            } catch (_: CancellationException) { lastOutcome = "cancelled"; log("取消 mode=$mode") }
            catch (failure: Throwable) { lastOutcome = "failed"; log("失敗 mode=$mode type=${failure.javaClass.simpleName}") }
            finally {
                try {
                    try { environment("end") }
                    finally {
                        sampling.set(false)
                        val observedSampler=sampler
                        observedSampler?.interrupt()
                        var interrupted=false
                        while(observedSampler?.isAlive == true) {
                            try { observedSampler.join() } catch(_:InterruptedException) { interrupted=true }
                        }
                        if(interrupted)Thread.currentThread().interrupt()
                        if (observeMemory) log("観測最大 managedBytes=$managedMax nativeBytes=$nativeMax intervalMs=100 PSSは別計測")
                    }
                } finally {
                    val owner=Thread.currentThread()
                    if(cancellation === stop)cancellation=null
                    busy.set(false)
                    main.post {
                        if(worker === owner) {
                            worker=null
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            stopSelfResult(latestStartId)
                        }
                    }
                }
            }
        }, "gpu-probe-owner")
        worker = nextWorker
        nextWorker.start()
        } catch(failure:Throwable) {
            lastOutcome = "failed"; stop.set(true); worker=null; cancellation=null; busy.set(false)
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelfResult(latestStartId)
            log("診断スレッド開始失敗 type=${failure.javaClass.simpleName}")
        }
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
            val faces = FaceMask(width,height,FloatArray(width*height))
            for (strength in listOf(.25,.5,1.0)) {
                checkpoint()
                fun cpuRun():Pair<LinearSrgbImage,Long> {
                val beforeCpu = System.nanoTime()
                val cpu = StageClearLook.renderFromBase(base,faces,strength).image
                val cpuNs = System.nanoTime()-beforeCpu
                    return cpu to cpuNs
                }
                fun gpuRun():Pair<LinearSrgbImage,Long> {
                val beforeGpu = System.nanoTime()
                // CPU全Lookと同じ範囲で肌fieldの準備も計測する。
                val skin = StageClearGlow.skinWeight(base)
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
                    return gpu to gpuNs
                }
                val records=if(reverse){val g=gpuRun();cpuRun() to g}else{val c=cpuRun();c to gpuRun()}
                val (cpu,cpuNs)=records.first;val (gpu,gpuNs)=records.second
                var max = 0; var changed = 0; var flatEdges = 0
                for (p in 0 until cpu.pixelCount) {
                    var delta = 0
                    repeat(3) { c -> delta = maxOf(delta,abs(quantize(cpu[p*3+c])-quantize(gpu[p*3+c]))) }
                    max = maxOf(max,delta); if(delta>0) changed++
                    if (dark && p%width>0 && quantize(cpu[p*3])!=quantize(cpu[(p-1)*3]) && quantize(gpu[p*3])==quantize(gpu[(p-1)*3])) flatEdges++
                }
                log("全Look reverse=$reverse width=$width dark=$dark strength=$strength cpuNs=$cpuNs gpuNs=$gpuNs max8=$max changed=$changed pixels=${cpu.pixelCount} newFlatEdges=$flatEdges")
                check(max<=2) { "プレビュー許容差を超えました" }
            }
        }
    }
    private fun runBlur12() {
        val width=4000; val height=3000
        val source:(Int,Int)->FloatArray={top,rows->FloatArray(width*rows*4){i->if(i%4==3)1f else sample((i/4)%width,top+(i/4)/width,width,height,i%4,false)}}
        var cpuChecksum=0.0;var gpuChecksum=0.0
        fun cpuRun()=CpuBlurProbe.run(width,height,StageClearGlowV1.BLOOM_KERNELS.first().first,source=source,
            consume={_,frame->for(i in frame.rgba.indices step 4)cpuChecksum+=frame.rgba[i]},cancelled={stop.get()})
        fun gpuRun()=GpuBlurProbe.run(width,height,StageClearGlowV1.BLOOM_KERNELS.first().first,source=source,
            consume={_,frame->for(i in frame.rgba.indices step 4)gpuChecksum+=frame.rgba[i]},cancelled={stop.get()})
        val records=if(reverse){val g=gpuRun();cpuRun() to g}else{val c=cpuRun();c to gpuRun()}
        val cpuNs=records.first;val result=records.second
        log("12MPぼかし単体 cpuNs=$cpuNs gpuNs=${result.elapsedNs} uploadNs=${result.uploadNs} drawNs=${result.drawNs} readbackNs=${result.readbackNs} passBytes=${result.largestPassBytes} renderer=${result.renderer} finite=${cpuChecksum.isFinite()&&gpuChecksum.isFinite()} reverse=$reverse quality=not_measured・finiteは品質一致を意味しない・全保存経路ではない")
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
            fun gpuRun():LinearSrgbImage {
                val started=System.nanoTime()
                val gpu=GpuDiagnosticOrton.prepared(image,soft,skin,.5,64){stop.get()}.output
                totalNs+=System.nanoTime()-started;contexts++
                return gpu
            }
            fun cpuRun():LinearSrgbImage {
                val started=System.nanoTime()
                val cpu=CpuOrtonPrepared.render(image,soft,skin,.5)
                cpuTotalNs+=System.nanoTime()-started
                return cpu
            }
            val records=if(reverse){val g=gpuRun();cpuRun() to g}else{val c=cpuRun();c to gpuRun()}
            val cpu=records.first;val gpu=records.second
            for(p in 0 until image.pixelCount){
                var delta=0;repeat(3){c->delta=maxOf(delta,abs(quantize(cpu[p*3+c])-quantize(gpu[p*3+c])))}
                max=maxOf(max,delta);if(delta>0)changed++
            }
            check(max<=1){"Orton単体の保存候補許容差を超えました"}
        }
        log("12MPOrton単体 reverse=$reverse gpuNs=$totalNs cpuPreparedNs=$cpuTotalNs contexts=$contexts max8=$max changed=$changed pixels=${width*height} 一定色・全保存経路ではない")
    }
    private fun quantize(value:Float):Int {
        val v=value.coerceIn(0f,1f).toDouble()
        return ((if(v<=.0031308)v*12.92 else 1.055*v.pow(1/2.4)-.055)*255).roundToInt().coerceIn(0,255)
    }
    companion object {
        private val busy=AtomicBoolean(false)
        @Volatile private var cancellation:AtomicBoolean?=null
        @Volatile internal var lastOutcome: String = "idle"
            private set
        internal fun active()=busy.get()
        fun cancel(){cancellation?.set(true)}
        fun cancelled()=cancellation?.get()==true
        private fun log(value:String){android.util.Log.i("GpuDiagnostic",value)}
    }
}