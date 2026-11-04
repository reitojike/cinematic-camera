package dev.cinematic.p0.rawcapture

import android.content.Context
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.File
import java.util.concurrent.Executors

/** One application-scoped owner, independent of Activity/camera recreation. No renderer is registered. */
@RequiresApi(29)
internal object AndroidRawCaptureQueue {
    @Volatile private var instance: RawCaptureQueue? = null

    fun get(context: Context): RawCaptureQueue = instance ?: synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
    }

    private fun create(app: Context): RawCaptureQueue {
        val root = File(app.noBackupFilesDir, "raw-captures")
        val rows = MediaStoreDngPendingRows(app)
        fun thread(name: String, action: Runnable) = Thread(action, name).apply { isDaemon = true }
        return RawCaptureQueue(
            root = root,
            sessionFactory = { id ->
                RawCaptureSession(
                    RawCaptureStore(root, id, AndroidAtomicJournalStorage(root, id)),
                    DngPublicationJournal(id, AndroidAtomicJournalStorage(root, id, CaptureJournalFile.DNG_PUBLICATION)), rows,
                )
            },
            prepareIo = Executors.newSingleThreadExecutor { thread("raw-capture-prepare", it) },
            completionIo = Executors.newFixedThreadPool(2) { thread("raw-capture-receipt", it) },
            handoffIo = Executors.newSingleThreadExecutor { thread("raw-dng-handoff", it) },
            main = app.mainExecutor,
            report = { Log.i("CinematicRaw", it) },
            recoveryIo = Executors.newSingleThreadExecutor { thread("raw-recovery", it).apply { priority = Thread.MIN_PRIORITY } },
        )
    }
}
