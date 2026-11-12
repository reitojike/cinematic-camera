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
        val detailed = CheckBox(this).apply { text = "内訳を分離（割当・同期条件が異なる別試験）" }
        panel.addView(detailed)
        for ((label, mode) in listOf("小型の正しさ確認" to "small", "1024px 全Look比較" to "preview", "12MP ぼかし単体" to "blur12", "12MP Orton単体" to "orton12")) {
            panel.addView(Button(this).apply {
                text = label
                setOnClickListener { startForegroundService(Intent(this@GpuDiagnosticActivity, GpuDiagnosticService::class.java).setAction(mode).putExtra("observeMemory",memory.isChecked).putExtra("reverse",reverse.isChecked).putExtra("detailedTiming",detailed.isChecked)) }
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
    private var detailedTiming = false
    private val timingMode get() = if (detailedTiming) "split-allocation-v1" else "baseline-v1"
    private var runId = 0L
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val mode = intent?.action
        if (mode !in setOf("small", "preview", "blur12", "orton12") || worker != null || PreviewParallelDiagnosticActivity.active() || dev.cinematic.p0.rawdiagnostic.RawDiagnosticActivity.activeOrHeld() || dev.cinematic.p0.photoroll.ForegroundExports.gate.active() != null) {
            if (worker == null) stopSelfResult(startId)
            return START_NOT_STICKY
        }
        if (!busy.compareAndSet(false,true)) { stopSelfResult(startId); return START_NOT_STICKY }
        lastOutcome = "running"
        cancellation = stop
        val observeMemory=intent?.getBooleanExtra("observeMemory",false) ?: false
        reverse=intent?.getBooleanExtra("reverse",false) ?: false
        detailedTiming=intent?.getBooleanExtra("detailedTiming",false) ?: false
        stop.set(false)
        runId = System.nanoTime()
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
                log("GPU計測 session=$runId mode=$mode timingMode=$timingMode observeMemory=$observeMemory reverse=$reverse")
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
            GpuDiagnosticSession(detailedTiming = detailedTiming) { stop.get() }.use { session ->
                val sessionId = "$runId-$dark"
                log("GPU準備 timingMode=$timingMode session=$sessionId width=$width preparationNs=${session.preparationNs} renderer=${session.renderer}")
                // 写真ごとの初回1回と、各強度でABBA（逆ならBAAB）の再利用4回。
                val strengths = listOf(.25) + listOf(.25,.5,1.0).flatMap { value -> List(4) { value } }
                for ((iteration, strength) in strengths.withIndex()) {
                    val phase = if (iteration == 0) "first" else "reused"
                    val comparisonReversed = reverse.xor(iteration > 0 && (iteration - 1) % 4 in 1..2)
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
                        val glow = GpuDiagnosticGlow.render(session,base,strength,skin)
                        val blurNs=checkNotNull(GpuProbeImages.lastWallNs.get())
                        val blurTimes=checkNotNull(GpuProbeImages.lastTimes.get())
                        var ortonNs=0L; var ortonPreparationNs=0L
                        var ortonDetail: GpuWorkTiming.Snapshot? = null
                        var uploadNs=blurTimes.uploadNs; var drawNs=blurTimes.drawNs; var readbackNs=blurTimes.readbackNs
                        val gpu = StageClearProtections.finish(glow,faces) {
                            val mist = StageClearMist.render(it,skin,strength)
                            val observation=GpuDiagnosticOrton.render(session,mist,skin,64) { stop.get() }
                            ortonNs+=observation.gpuWallNs;ortonPreparationNs+=observation.cpuPrepareNs
                            observation.detail?.let { ortonDetail = ortonDetail?.plus(it) ?: it }
                            uploadNs+=observation.uploadNs;drawNs+=observation.drawNs;readbackNs+=observation.readbackNs
                            StageClearCharacter.render(observation.output,strength)
                        }.image
                        val gpuNs = System.nanoTime()-beforeGpu
                        val twoStagesNs = blurNs + ortonNs
                        log("対象GPU2工程 timingMode=$timingMode session=$sessionId phase=$phase iteration=$iteration reverse=$comparisonReversed width=$width dark=$dark strength=$strength blurNs=$blurNs ortonNs=$ortonNs sumNs=$twoStagesNs uploadNs=$uploadNs drawNs=$drawNs readbackNs=$readbackNs ortonCpuPreparationNs=$ortonPreparationNs preparationExcluded=true transferSyncReadbackIncluded=true")
                        blurTimes.detail?.let { log("GPU内訳 stage=blur session=$sessionId phase=$phase iteration=$iteration width=$width dark=$dark strength=$strength reverse=$comparisonReversed ${it.fields()}") }
                        ortonDetail?.let { log("GPU内訳 stage=orton session=$sessionId phase=$phase iteration=$iteration width=$width dark=$dark strength=$strength reverse=$comparisonReversed ${it.copy(totalNs = ortonNs).fields()}") }
                        if (iteration == 0) log("GPU初回 timingMode=$timingMode session=$sessionId preparationNs=${session.preparationNs} firstTwoStagesIncludingPreparationNs=${session.preparationNs+twoStagesNs} firstLookIncludingPreparationNs=${session.preparationNs+gpuNs} teardownExcluded=true")
                        return gpu to gpuNs
                    }
                    val records=if(comparisonReversed){val g=gpuRun();cpuRun() to g}else{val c=cpuRun();c to gpuRun()}
                    val (cpu,cpuNs)=records.first;val (gpu,gpuNs)=records.second
                    check(cpu.width == gpu.width && cpu.height == gpu.height) { "GPU comparison dimensions differ" }
                    var max = 0; var changed = 0; var flatEdges = 0
                    for (p in 0 until cpu.pixelCount) {
                        var delta = 0
                        repeat(3) { c -> delta = maxOf(delta,abs(quantize(cpu[p*3+c])-quantize(gpu[p*3+c]))) }
                        max = maxOf(max,delta); if(delta>0) changed++
                        if (dark && p%width>0 && quantize(cpu[p*3])!=quantize(cpu[(p-1)*3]) && quantize(gpu[p*3])==quantize(gpu[(p-1)*3])) flatEdges++
                    }
                    log("CPU_REFERENCE_ONLY 全Look timingMode=$timingMode session=$sessionId phase=$phase iteration=$iteration reverse=$comparisonReversed width=$width dark=$dark strength=$strength cpuNs=$cpuNs gpuNs=$gpuNs max8=$max changed=$changed pixels=${cpu.pixelCount} newFlatEdges=$flatEdges")
                }
            }
        }
    }
    private fun runBlur12() {
        val width=4000; val height=3000
        val source:(Int,Int)->FloatArray={top,rows->FloatArray(width*rows*4){i->if(i%4==3)1f else sample((i/4)%width,top+(i/4)/width,width,height,i%4,false)}}
        GpuDiagnosticSession(detailedTiming = detailedTiming) { stop.get() }.use { session ->
            log("GPU準備 timingMode=$timingMode session=$runId mode=blur12 preparationNs=${session.preparationNs} renderer=${session.renderer}")
            repeat(4) { iteration ->
                checkpoint()
                val phase=if(iteration==0) "first" else "reused"
                var cpuChecksum=0.0;var gpuChecksum=0.0
                fun cpuRun()=CpuBlurProbe.run(width,height,StageClearGlowV1.BLOOM_KERNELS.first().first,source=source,
                    consume={_,frame->for(i in frame.rgba.indices step 4)cpuChecksum+=frame.rgba[i]},cancelled={stop.get()})
                fun gpuRun()=GpuBlurProbe.run(session,width,height,StageClearGlowV1.BLOOM_KERNELS.first().first,source=source,
                    consume={_,frame->for(i in frame.rgba.indices step 4)gpuChecksum+=frame.rgba[i]},cancelled={stop.get()})
                val records=if(reverse){val g=gpuRun();cpuRun() to g}else{val c=cpuRun();c to gpuRun()}
                val cpuNs=records.first;val result=records.second
                if(iteration==0)log("GPU初回 timingMode=$timingMode session=$runId mode=blur12 preparationNs=${session.preparationNs} firstIncludingPreparationNs=${session.preparationNs+result.elapsedNs} teardownExcluded=true")
                result.detail?.let { log("GPU内訳 stage=blur12 session=$runId phase=$phase iteration=$iteration reverse=$reverse ${it.fields()}") }
                log("12MPぼかし単体 timingMode=$timingMode session=$runId phase=$phase iteration=$iteration cpuNs=$cpuNs gpuNs=${result.elapsedNs} uploadNs=${result.uploadNs} drawNs=${result.drawNs} readbackNs=${result.readbackNs} passBytes=${result.largestPassBytes} renderer=${result.renderer} finite=${cpuChecksum.isFinite()&&gpuChecksum.isFinite()} reverse=$reverse quality=not_measured・finiteは品質一致を意味しない・全保存経路ではない")
            }
        }
    }
    /** 12MP負荷・一定色の品質試験。全Look/JPEGではなく、同じ文脈で全帯・4回を処理。 */
    private fun runOrton12() {
        val width=4000; val height=3000; val source=floatArrayOf(.2f,.3f,.4f)
        GpuDiagnosticSession(detailedTiming = detailedTiming) { stop.get() }.use { session ->
            log("GPU準備 timingMode=$timingMode session=$runId mode=orton12 preparationNs=${session.preparationNs} renderer=${session.renderer}")
            repeat(4) { iteration ->
                val phase=if(iteration==0) "first" else "reused"
                var totalNs=0L; var cpuTotalNs=0L; var max=0; var changed=0
                var detail: GpuWorkTiming.Snapshot? = null
                var uploadNs=0L; var drawNs=0L; var readbackNs=0L
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
                        val gpu=GpuDiagnosticOrton.prepared(session,image,soft,skin,.5,64){stop.get()}
                        totalNs+=System.nanoTime()-started
                        gpu.detail?.let { detail = detail?.plus(it) ?: it }
                        uploadNs+=gpu.uploadNs;drawNs+=gpu.drawNs;readbackNs+=gpu.readbackNs
                        return gpu.output
                    }
                    fun cpuRun():LinearSrgbImage {
                        val started=System.nanoTime()
                        val cpu=CpuOrtonPrepared.render(image,soft,skin,.5)
                        cpuTotalNs+=System.nanoTime()-started
                        return cpu
                    }
                    val records=if(reverse){val g=gpuRun();cpuRun() to g}else{val c=cpuRun();c to gpuRun()}
                    val cpu=records.first;val gpu=records.second
                    check(cpu.width == gpu.width && cpu.height == gpu.height) { "GPU comparison dimensions differ" }
                    for(p in 0 until image.pixelCount){
                        var delta=0;repeat(3){c->delta=maxOf(delta,abs(quantize(cpu[p*3+c])-quantize(gpu[p*3+c])))}
                        max=maxOf(max,delta);if(delta>0)changed++
                    }
                }
                if(iteration==0)log("GPU初回 timingMode=$timingMode session=$runId mode=orton12 preparationNs=${session.preparationNs} firstIncludingPreparationNs=${session.preparationNs+totalNs} teardownExcluded=true")
                detail?.let { log("GPU内訳 stage=orton12 session=$runId phase=$phase iteration=$iteration reverse=$reverse ${it.copy(totalNs = totalNs).fields()}") }
                log("CPU_REFERENCE_ONLY 12MPOrton単体 timingMode=$timingMode session=$runId phase=$phase iteration=$iteration reverse=$reverse gpuNs=$totalNs uploadNs=$uploadNs drawNs=$drawNs readbackNs=$readbackNs cpuPreparedNs=$cpuTotalNs contexts=1 max8=$max changed=$changed pixels=${width*height} 一定色・全保存経路ではない")
            }
        }
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
