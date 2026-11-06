package dev.cinematic.p0.photoroll

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.annotation.RequiresApi
import dev.cinematic.p0.rawcapture.AutoDevelopBlocker
import dev.cinematic.p0.rawcapture.AutoDevelopConditions
import dev.cinematic.p0.rawcapture.AutoDevelopPolicy
import dev.cinematic.p0.rawcapture.ForegroundExportGate
import java.io.IOException
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** Existing handle adapter only. No bitmap, URI grant, recipe, or publication authority in an Intent. */
internal interface ForegroundExportWork {
    val identity: Any
    fun reserveForeground(conditions: AutoDevelopConditions): Boolean
    /** All producer owners, including retained row executors; closed files alone are insufficient. */
    val resourcesClosed: Boolean
    fun advance(conditions: () -> AutoDevelopConditions, stop: () -> Boolean): CompletableFuture<Unit>
    fun observe(conditions: AutoDevelopConditions)
    fun requestStop()
    fun finished(failure: Throwable?)
}

internal fun foregroundExportType(api: Int): Int = if (api >= 35)
    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

/** One process-wide save. Process death deliberately loses delivery authority; existing journals
 * decide recovery on the next explicit save. A null/redelivered service intent never renders.
 */
@RequiresApi(29)
internal object ForegroundExports {
    val gate = ForegroundExportGate<ForegroundExportWork>()
    val changes = MutableStateFlow(0L)
    fun changed() { changes.update { it + 1 } }
    fun start(context: Context, work: ForegroundExportWork, conditions: AutoDevelopConditions): Boolean {
        if (AutoDevelopPolicy.blockers(conditions).isNotEmpty()) return false
        val ticket = gate.reserve(work, UUID.randomUUID().toString()) ?: return false
        if (!work.reserveForeground(conditions)) { gate.finish(ticket, true); return false }
        changed()
        try {
            context.applicationContext.startForegroundService(Intent(context.applicationContext, ForegroundExportService::class.java)
                .setAction(ForegroundExportService.START).putExtra(ForegroundExportService.TOKEN, ticket.token))
        } catch (failure: Exception) {
            gate.stop(ticket)
            gate.finish(ticket, work.resourcesClosed)
            work.finished(failure)
            changed()
            return false
        }
        return true
    }
    fun cancel(work: ForegroundExportWork): Boolean {
        val ticket = gate.active()?.takeIf { it.value.identity === work.identity } ?: return false
        gate.stop(ticket)
        ticket.value.requestStop()
        changed()
        return true
    }
    fun owns(work: ForegroundExportWork) = gate.active()?.value?.identity === work.identity
}

/** A lifecycle host for the existing serial save owner, not another processing executor. */
@android.annotation.TargetApi(29)
class ForegroundExportService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var ticket: ForegroundExportGate.Ticket<ForegroundExportWork>? = null
    private var observer: PhotoRollResourceObserver? = null
    private var startedAt = 0L
    private var startId = 0
    private var destroyed = false
    private var finished = false
    private val poll = Runnable { awaitObservation() }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT < 29) { stopSelfResult(startId); return START_NOT_STICKY }
        this.startId = startId // Cancel/duplicate deliveries also advance Android's service start id.
        val incoming = ForegroundExports.gate.find(intent?.getStringExtra(TOKEN))
        if (finished && incoming != null && incoming !== ticket) {
            ticket = null
            finished = false
        }
        if (incoming == null) { if (ticket == null || finished) stopSelfResult(startId); return START_NOT_STICKY }
        if (intent?.action == CANCEL) {
            ForegroundExports.cancel(incoming.value)
            if (ticket == null) { ticket = incoming; complete(incoming, IOException("Save cancelled before start")) }
            return START_NOT_STICKY
        }
        if (intent?.action != START || ticket != null) return START_NOT_STICKY
        ticket = incoming
        try {
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "JPEG保存", NotificationManager.IMPORTANCE_LOW))
            // Notification permission denial does not authorize skipping the mandatory FGS notification.
            startForeground(NOTIFICATION, notification(incoming.token), foregroundExportType(Build.VERSION.SDK_INT))
            observer = PhotoRollResourceObserver(applicationContext, { conditions -> incoming.value.observe(conditions) },
                serviceOwned = true).also { it.start() }
            startedAt = SystemClock.elapsedRealtime()
            awaitObservation()
        } catch (failure: Exception) { complete(incoming, failure) }
        return START_NOT_STICKY
    }

    private fun awaitObservation() {
        val own = ticket ?: return
        if (destroyed || finished) return
        if (own.stopRequested) { complete(own, IOException("Save cancelled before admission")); return }
        val observed = observer?.snapshot()?.developConditions(rendererAvailable = true) ?: AutoDevelopConditions()
        if ((AutoDevelopPolicy.blockers(observed) - AutoDevelopBlocker.FOREGROUND).isNotEmpty()) {
            if (SystemClock.elapsedRealtime() - startedAt >= 2_000L) {
                complete(own, IOException("Export resources unconfirmed"))
            } else main.postDelayed(poll, 100L)
            return
        }
        if (!ForegroundExports.gate.begin(own)) { complete(own, IOException("Export delivery not current")); return }
        try {
            own.value.advance({ observer?.snapshot()?.developConditions(rendererAvailable = true) ?: AutoDevelopConditions() },
                { own.stopRequested || destroyed }).whenComplete { _, failure ->
                main.post { complete(own, failure) }
            }
        } catch (failure: Exception) { complete(own, failure) }
    }

    private fun complete(own: ForegroundExportGate.Ticket<ForegroundExportWork>, failure: Throwable?) {
        if (finished || ticket !== own) return
        finished = true
        // The future returned by the existing handle completes only AFTER its cleanup finally.
        // Even exceptional completion does not let us assume closure: an unclosed owner stays held.
        val closed = own.value.resourcesClosed
        if (!closed) { ForegroundExports.gate.stop(own); own.value.requestStop() }
        observer?.stop(); observer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelfResult(startId)
        ForegroundExports.gate.finish(own, closed)
        own.value.finished(failure ?: if (!closed) IOException("Export resources remain held") else null)
        ForegroundExports.changed()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        ticket?.let { ForegroundExports.cancel(it.value) }
        // OS deadline cannot wait for native/FD cleanup. The process gate/handle keep ownership.
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        destroyed = true
        main.removeCallbacks(poll) // Keep a pending worker completion: it must reconcile actual closure.
        val own = ticket
        if (!finished && own != null) {
            ForegroundExports.cancel(own.value)
            if (!own.started) complete(own, IOException("Export service stopped before admission"))
        }
        observer?.stop(); observer = null
        super.onDestroy()
    }

    private fun notification(token: String): Notification {
        val cancel = PendingIntent.getService(this, 0, Intent(this, ForegroundExportService::class.java)
            .setAction(CANCEL).setData(android.net.Uri.parse("cinematic-export://cancel/$token")).putExtra(TOKEN, token),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 0, Intent(this, PhotoRollActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("試験品質JPEGを保存中").setContentText("アプリを離れても継続します。撮影開始時は中断します")
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "保存を取り消す", cancel).build()).build()
    }

    internal companion object {
        const val START = "dev.cinematic.p0.export.START"
        const val CANCEL = "dev.cinematic.p0.export.CANCEL"
        const val TOKEN = "export-token"
        private const val CHANNEL = "manual-jpeg-export"
        private const val NOTIFICATION = 241
    }
}
