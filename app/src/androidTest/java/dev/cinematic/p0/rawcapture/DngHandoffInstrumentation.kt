package dev.cinematic.p0.rawcapture

import android.app.Activity
import android.app.Instrumentation
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.annotation.RequiresApi
import java.io.File
import java.io.IOException
import java.util.UUID

/** Explicit opt-in on a disposable emulator only. Uses artificial bytes, never camera/photo input. */
class DngHandoffInstrumentation : Instrumentation() {
    private var isolated = false
    private var stage = "setup"

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        isolated = arguments?.getString("isolated") == "true"
        start()
    }

    override fun onStart() {
        val result = Bundle()
        try {
            check(isolated && Build.HARDWARE in setOf("goldfish", "ranchu"))
            check(Build.VERSION.SDK_INT >= 29)
            smoke()
            result.putString("handoff_smoke", "PASS")
            finish(Activity.RESULT_OK, result)
        } catch (failure: Throwable) {
            // No paths, URIs, metadata, exception messages or device identifiers in the result.
            result.putString("handoff_smoke", "FAIL")
            result.putString("stage", stage)
            result.putString("failure_type", failure.javaClass.simpleName)
            finish(Activity.RESULT_CANCELED, result)
        }
    }

    @RequiresApi(29)
    private fun smoke() {
        val context = targetContext
        val root = File(context.noBackupFilesDir, "dng-handoff-smoke")
        val id = "smoke_${UUID.randomUUID().toString().replace("-", "")}"
        val original = "synthetic byte-protocol test, not a decodable DNG".toByteArray()
        val jpeg = "synthetic provisional JPEG".toByteArray()
        fun store() = RawCaptureStore(root, id, AndroidAtomicJournalStorage(root, id))
        fun journal() = DngPublicationJournal(id, AndroidAtomicJournalStorage(root, id, CaptureJournalFile.DNG_PUBLICATION))
        var sourceStore = store()
        stage = "receipt"
        sourceStore.create(setOf(RawOutput.DNG, RawOutput.JPEG))
        val raw = sourceStore.openOutput(0, RawOutput.DNG)
        val preview = sourceStore.openOutput(0, RawOutput.JPEG)
        raw.use { it.write(original) }
        preview.use { it.write(jpeg) }
        sourceStore.received(0, raw)
        sourceStore.received(1, preview)
        sourceStore.verifyOriginal(2)
        var publication = journal()
        val native = MediaStoreDngPendingRows(context)
        val lostAck = object : DngHandoffRows by native {
            override fun publish(target: DngPublicationTarget, uri: String) {
                native.publish(target, uri)
                throw IOException("Synthetic publication acknowledgement loss")
            }
        }
        stage = "publish_ack_loss"
        val first = DngHandoffCoordinator(publication, lostAck, sourceStore.handoffSource(publication))
        var expectedFailure = false
        try { first.advance() } catch (_: IOException) { expectedFailure = true }
        check(expectedFailure && !first.hasUnreleasedResources)
        val publishing = requireNotNull(publication.read())
        check(publishing.phase == DngPublicationPhase.PUBLISHING)
        check(File(File(root, id), RawOutput.DNG.fileName).readBytes().contentEquals(original))
        val uri = requireNotNull(publishing.uri)
        check(publishing.target.accepts(requireNotNull(native.inspect(uri)), false))

        stage = "reconcile_release"
        sourceStore = store()
        publication = journal()
        val resumed = DngHandoffCoordinator(publication, native, sourceStore.handoffSource(publication))
        val done = resumed.advance()
        check(done.phase == DngPublicationPhase.SHARED_ONLY && done.uri == uri)
        check(!File(File(root, id), RawOutput.DNG.fileName).exists())
        check(File(File(root, id), RawOutput.JPEG.fileName).readBytes().contentEquals(jpeg))
        DngContentVerifier.verify(done.fingerprint, { native.openRead(done.target, uri, false) })
        check(native.find(done.target).size == 1)

        stage = "synthetic_cleanup"
        // Only the just-created synthetic row; never query/delete arbitrary images or folders.
        check(done.target.accepts(requireNotNull(native.inspect(uri)), false))
        check(context.contentResolver.delete(Uri.parse(uri), null, null) == 1)
        check(resumed.advance() == done) // Historical completion never recreates an externally deleted row.
        check(native.find(done.target).isEmpty())
    }
}
