package dev.cinematic.p0.rawcapture

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.provider.OpenableColumns
import dev.cinematic.p0.photoroll.PhotoRollResourceObserver
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

internal data class DngImportScreenState(val busy: Boolean = false, val message: String? = null,
    val items: List<DngImportItem> = emptyList(), val readyUri: String? = null)

/** One process queue; no background service, automatic copy, or private DNG mirror. */
internal class AndroidDngImports private constructor(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val root = File(app.noBackupFilesDir.canonicalFile, "dng-imports")
    private val files = DngImportJournalFiles(root)
    private val rows = MediaStoreDngPendingRows(app)
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "dng-import").apply { isDaemon = true } }
    private val admitted = AtomicBoolean(false)
    private val paused = AtomicBoolean(false)
    private val cancelled = AtomicBoolean(false)
    private val mutable = MutableStateFlow(DngImportScreenState())
    val state = mutable.asStateFlow()
    @Volatile private var owner: DngImportOwner? = null
    private var preparation = DngIoResources()
    private var token: AutoDevelopCoordinator.Attempt? = null

    private var screenGeneration = 0L
    private var requestGeneration = 0L

    @Synchronized fun pause() {
        screenGeneration++
        paused.set(true)
        mutable.update { it.copy(readyUri = null) }
    }
    @Synchronized fun consumeReady(uri: String): Boolean {
        if (stopped() || mutable.value.busy || requestGeneration != screenGeneration || mutable.value.readyUri != uri) return false
        mutable.update { it.copy(readyUri = null) }
        return true
    }

    fun refresh() = submit(null) { publish("取り込み済み原本と中断した取り込みを確認しました") }

    fun select(uri: Uri, flags: Int, conditions: () -> AutoDevelopConditions) = submit(conditions) {
        require(uri.scheme == "content")
        val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
        }
        require(name?.endsWith(".dng", ignoreCase = true) == true) { "Select a DNG" }
        val fingerprint = DngContentVerifier.identifyBounded(DngImportRecord.MAX_SOURCE_BYTES,
            { preparation.input(openSource(uri.toString())) }, ::stopped)
        releasePreparation()
        val records = records()
        val unfinished = records.filter { it.phase !in setOf(DngImportPhase.READY, DngImportPhase.CANCELLED) }
        if (unfinished.size > 1) throw IOException("Ambiguous import records")
        if (unfinished.any { it.fingerprint != fingerprint }) throw IOException("Resolve earlier import first")
        var record = unfinished.singleOrNull()
            ?: records.firstOrNull { it.phase == DngImportPhase.READY && it.fingerprint == fingerprint }
        if (record?.phase == DngImportPhase.READY) {
            advance(record, conditions) // Existing content must still be readable and byte-identical.
            return@submit
        }
        if (record == null) {
            val id = UUID.randomUUID().toString()
            record = DngImportRecord(DngImportTarget(id, UUID.randomUUID().toString().replace("-", "")),
                uri.toString(), fingerprint, DngImportGrant.SESSION_ONLY)
            record = journal(id).prepare(record)
        } else if (!record.stopRequested && record.phase in setOf(DngImportPhase.PREPARED, DngImportPhase.INSERTING, DngImportPhase.COPYING)) {
            record = grants(record).reselect(record, uri.toString(), fingerprint, persistableRead(flags), otherGrantUses(record))
        }
        record = grants(record).retain(record, persistableRead(flags))
        advance(record, conditions)
    }

    fun resume(id: String, conditions: () -> AutoDevelopConditions) = submit(conditions) {
        advance(requireNotNull(journal(id).read()), conditions)
    }

    @Synchronized fun cancel(id: String?, conditions: () -> AutoDevelopConditions) {
        // A late tap from the busy UI must invalidate delivery even after the worker finished.
        cancelled.set(true)
        mutable.update { it.copy(readyUri = null) }
        if (admitted.get()) {
            owner?.requestStop()
            return
        }
        if (id == null) return
        // Closure/reconciliation must remain possible after ENOSPC. A durable stop forbids copying.
        submit(null) {
            val record = requireNotNull(journal(id).read())
            if (record.phase in setOf(DngImportPhase.READY, DngImportPhase.CANCELLED)) {
                val released = grants(record).release(record, otherGrantUses(record))
                publish(if (released.grant == DngImportGrant.ACQUIRED) "別の取り込みで使用中のため権限の解放を保留しました"
                    else "取り込みの読み取り権限を確認しました。共有原本は保持します")
                return@submit
            }
            journal(id).stop(record)
            advance(requireNotNull(journal(id).read()), conditions, openCompleted = false)
        }
    }

    private fun advance(record: DngImportRecord, conditions: () -> AutoDevelopConditions, openCompleted: Boolean = true) {
        if (stopped()) throw DngVerificationStopped()
        val id = record.target.operationId
        val operation = DngImportOwner(journal(id), DngImportSource(::openSource), rows,
            interrupted = ::stopped, spaceAvailable = { bytes ->
                conditions().storageAllowed == true && availableBytes() >= bytes + PhotoRollResourceObserver.PRIVATE_REQUIRED_BYTES
            })
        owner = operation
        val result = operation.advance()
        val current = requireNotNull(journal(id).read())
        if (result in setOf(DngImportResult.READY, DngImportResult.CANCELLED)) grants(current).release(current, otherGrantUses(current))
        val message = when (result) {
            DngImportResult.READY -> "共有原本への取り込みが完了しました。現像できない場合も原本は保持します"
            DngImportResult.CANCELLED -> "取り込みを取り消しました。選択元は変更していません"
            DngImportResult.WAIT_SOURCE -> "元のDNGをFilesから選び直してください"
            DngImportResult.HOLD -> "取り込み先の状態を確認できません。追加コピーせず保留しています"
        }
        publish(message, if (result == DngImportResult.READY && openCompleted &&
            (record.phase == DngImportPhase.READY || !current.stopRequested) && !stopped()) current.uri else null)
    }

    @Synchronized private fun submit(conditions: (() -> AutoDevelopConditions)?, action: () -> Unit) {
        if (!admitted.compareAndSet(false, true)) return
        requestGeneration = screenGeneration
        paused.set(false); cancelled.set(false)
        mutable.update { it.copy(busy = true, readyUri = null, message = "取り込みを確認中…") }
        executor.execute {
            var failure: Throwable? = null
            try {
                releaseRetained()
                if (conditions != null) {
                    if (paused.get()) throw DngVerificationStopped()
                    token = DevelopExecution.tryStartObserved(conditions()) ?: throw IOException("Import admission unavailable")
                    DevelopExecution.registerStop(requireNotNull(token)) { pause() }
                }
                action()
            } catch (error: Throwable) {
                failure = error
                mutable.update { it.copy(readyUri = null, message = when (error) {
                    is SecurityException -> "読み取り権限がありません。同じDNGを選び直してください"
                    is DngContentChanged -> "原本のサイズまたは内容を確認できません。64MiB以下の同じDNGを選び直してください"
                    is DngVerificationStopped -> "取り込みを中断しました。再開または取消で状態を確認してください"
                    else -> "取り込みを完了できません。容量・原本・権限を確認して再開してください。不明な公開結果は保留します"
                }) }
            } finally {
                try { releaseRetained() } catch (cleanup: Throwable) {
                    failure?.let { if (it !== cleanup) it.addSuppressed(cleanup) }
                    mutable.update { it.copy(readyUri = null, message = "資源の解放を確認できません。再開で回収を再試行してください") }
                }
                try { val refreshed = items(); mutable.update { it.copy(items = refreshed) } } catch (_: Exception) {
                    mutable.update { it.copy(readyUri = null, message = "取り込み記録を確認できません。新しいコピーは保留してください") }
                }
                synchronized(this@AndroidDngImports) {
                    admitted.set(false)
                    mutable.update { it.copy(busy = false) }
                }
            }
        }
    }

    private fun stopped(): Boolean = paused.get() || cancelled.get() || token?.let(DevelopExecution::shouldStop) == true
    private fun openSource(uri: String) = resolver.openInputStream(Uri.parse(uri)) ?: throw IOException("Import source unavailable")
    private fun availableBytes() = StatFs(Environment.getExternalStorageDirectory().absolutePath).availableBytes

    private fun releasePreparation() {
        val failure = preparation.releaseAll()
        if (failure != null) throw failure
        check(!preparation.hasOpenResources)
        preparation = DngIoResources()
    }
    private fun releaseRetained() {
        var failure: Throwable? = null
        try { owner?.releaseResources() } catch (error: Throwable) { failure = error }
        try { releasePreparation() } catch (error: Throwable) {
            if (failure == null) failure = error else if (failure !== error) failure.addSuppressed(error)
        }
        if (owner?.hasUnreleasedResources != true && !preparation.hasOpenResources) {
            owner = null
            token?.let { check(DevelopExecution.completeAfterResourcesClosed(it)) }
            token = null
        }
        failure?.let { throw it }
    }

    private fun journal(id: String) = files.journal(id)
    private fun records() = files.records()
    private fun items() = records().mapNotNull { it.importItem() }
    @Synchronized private fun publish(message: String, uri: String? = null) {
        mutable.update { it.copy(message = message, items = items(), readyUri = uri.takeIf { requestGeneration == screenGeneration && !stopped() }) }
    }

    private fun persistableRead(flags: Int) = flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION != 0 &&
        flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
    private fun otherGrantUses(record: DngImportRecord) = records().any {
        it.target != record.target && it.sourceUri == record.sourceUri &&
            it.phase !in setOf(DngImportPhase.READY, DngImportPhase.CANCELLED)
    }
    private fun grants(record: DngImportRecord) = DngImportReadGrants(journal(record.target.operationId),
        object : DngImportReadGrantPort {
            override fun held(uri: String) = resolver.persistedUriPermissions.any { it.uri == Uri.parse(uri) && it.isReadPermission }
            override fun takeRead(uri: String) = resolver.takePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
            override fun releaseRead(uri: String) = resolver.releasePersistableUriPermission(Uri.parse(uri), Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })

    companion object {
        @Volatile private var instance: AndroidDngImports? = null
        fun get(context: Context): AndroidDngImports = instance ?: synchronized(this) {
            instance ?: AndroidDngImports(context).also { instance = it }
        }
    }
}
