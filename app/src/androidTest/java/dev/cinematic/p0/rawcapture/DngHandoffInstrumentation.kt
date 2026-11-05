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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Explicit opt-in on a disposable emulator only. Uses artificial bytes, never camera/photo input. */
class DngHandoffInstrumentation : Instrumentation() {
    private var isolated = false
    private var stage = "setup"
    private var queueObserver: RawCaptureObserver? = null

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
            queueSmoke()
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

    @RequiresApi(29)
    private fun queueSmoke() {
        stage = "camera_owner_lifecycle"
        runOnMainSync {
            val owner = object : androidx.lifecycle.LifecycleOwner {
                override val lifecycle = androidx.lifecycle.LifecycleRegistry(this)
            }
            val probe = dev.cinematic.p0.CameraProbe(targetContext, owner)
            try {
                probe.capture() // The default entry remains safe before controls become ready.
                val policy = RenderPolicyVersion("synthetic-native", 1)
                val strict = CaptureDevelopIntent(RenderRecipeSnapshot("synthetic-native", "fixed"),
                    "synthetic-native", AutoRenderQuality.FINAL_ORIGINAL_STRICT,
                    OriginalRenderDeclaration(policy, policy, policy, policy, -4.0, 4.0))
                check(runCatching { probe.capture(strict) }.exceptionOrNull() is IllegalArgumentException)
            } finally { probe.close() }
        }
        val queue = AndroidRawCaptureQueue.get(targetContext)
        val camera = queue.newCameraOwner()
        camera.foreground(true)
        val id = "queue_${UUID.randomUUID().toString().replace("-", "")}"
        val original = "synthetic queue original".toByteArray()
        val jpeg = "synthetic queue JPEG".toByteArray()
        val policy = RenderPolicyVersion("synthetic-native", 1)
        val intent = CaptureDevelopIntent(RenderRecipeSnapshot("synthetic-native", "fixed"),
            "synthetic-native", AutoRenderQuality.FINAL_ORIGINAL_STRICT,
            OriginalRenderDeclaration(policy, policy, policy, policy, -4.0, 4.0))
        val received = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val failure = AtomicReference<String?>()
        val result = AtomicReference<DngPublicationRecord?>()
        val observer = RawCaptureObserver { ticket, notice ->
            try {
                when (notice) {
                    is RawCaptureNotice.Prepared -> {
                        notice.outputs.getValue(RawOutput.DNG).write(original)
                        notice.outputs.getValue(RawOutput.JPEG).write(jpeg)
                        queue.received(ticket, RawOutput.JPEG)
                        queue.received(ticket, RawOutput.DNG)
                    }
                    is RawCaptureNotice.Received -> if (notice.receipt.phase == CaptureReceiptPhase.FILES_RECEIVED) received.countDown()
                    is RawCaptureNotice.Shared -> { result.set(notice.record); finished.countDown() }
                    is RawCaptureNotice.Failed -> {
                        failure.set(notice.stage); received.countDown(); finished.countDown()
                    }
                }
            } catch (t: Throwable) {
                failure.set(t.javaClass.simpleName); received.countDown(); finished.countDown()
            }
        }
        queueObserver = observer
        try {
            stage = "queue_receipts"
            val ticket = queue.prepare(id, setOf(RawOutput.DNG, RawOutput.JPEG), observer, intent)
            check(received.await(15, TimeUnit.SECONDS) && failure.get() == null)
            check(ticket.session!!.receipt()!!.originalFingerprint == null) // Camera reservation still holds.
            check(queue.readDevelopRequest(id).get(15, TimeUnit.SECONDS) == null)
            stage = "queue_handoff"
            camera.update(false)
            check(finished.await(15, TimeUnit.SECONDS) && failure.get() == null)
            val done = requireNotNull(result.get())
            check(done.phase == DngPublicationPhase.SHARED_ONLY)
            val request = requireNotNull(queue.readDevelopRequest(id).get(15, TimeUnit.SECONDS))
            check(request.captureId == id && request.generation == 1L && request.source.fingerprint == done.fingerprint)
            check(request.recipe == intent.recipe && request.originalDeclaration == intent.originalDeclaration)
            val directory = File(File(targetContext.noBackupFilesDir, "raw-captures"), id)
            check(!File(directory, RawOutput.DNG.fileName).exists())
            check(File(directory, RawOutput.JPEG.fileName).readBytes().contentEquals(jpeg))
            val native = MediaStoreDngPendingRows(targetContext)
            val uri = requireNotNull(done.uri)
            DngContentVerifier.verify(done.fingerprint, { native.openRead(done.target, uri, false) })
            check(native.find(done.target).size == 1)
            stage = "queue_synthetic_cleanup"
            check(done.target.accepts(requireNotNull(native.inspect(uri)), false))
            check(targetContext.contentResolver.delete(Uri.parse(uri), null, null) == 1)
        } finally { camera.close(); queueObserver = null }
    }
}
