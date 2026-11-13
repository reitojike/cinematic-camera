package dev.cinematic.p0.rawcapture

import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test

class JpegReconciliationTest {
    @Test fun exactPublicAndPendingAreDistinguishedWithoutChangingUnknown() {
        for (pending in listOf(false, true)) {
            val f = Fixture()
            f.row = f.row.copy(pending = pending)
            val result = f.observe()
            assertEquals(if (pending) JpegReconciliationFinding.PENDING_BYTES_MATCH
                else JpegReconciliationFinding.PUBLIC_BYTES_MATCH, result.finding)
            assertEquals(f.previous, result.previous)
            assertEquals(JpegPublicationPhase.UNKNOWN, result.previous.phase)
            assertEquals(f.row.uri, result.uri)
            assertFalse(f.resources.hasOpenResources)
        }
    }

    @Test fun emptyPartialChangedAndOversizedRowsNeverProvePublication() {
        for (bytes in listOf(byteArrayOf(), byteArrayOf(1), byteArrayOf(4, 3, 2, 1), byteArrayOf(1, 2, 3, 4, 5))) {
            for (pending in listOf(false, true)) {
                val f = Fixture()
                f.bytes = bytes
                f.row = f.row.copy(pending = pending)
                assertEquals(if (pending) JpegReconciliationFinding.PENDING_BYTES_DIFFER
                    else JpegReconciliationFinding.PUBLIC_BYTES_DIFFER, f.observe().finding)
                assertFalse(f.resources.hasOpenResources)
            }
        }
    }

    @Test fun missingAndAmbiguousAreFactsNotUnpublishedProof() {
        val f = Fixture()
        f.candidates = { emptyList() }
        assertEquals(JpegReconciliationFinding.NO_CANDIDATE, f.observe().finding)
        f.candidates = { listOf(f.row, f.row) }
        assertEquals(JpegReconciliationFinding.AMBIGUOUS, f.observe().finding)
        assertEquals(0, f.reads)
    }

    @Test fun allTargetFieldsOwnershipAndRecordedUriMustMatch() {
        val f = Fixture()
        val row = f.row
        for (changed in listOf(row.copy(uri = row.uri + "1"), row.copy(ownedByApp = false),
            row.copy(displayName = "other.jpg"), row.copy(volume = "external"),
            row.copy(relativePath = "Pictures/"), row.copy(mimeType = "image/png"),
            row.copy(pending = null), row.copy(trashed = true), row.copy(trashed = null))) {
            f.row = changed
            assertEquals(JpegReconciliationFinding.METADATA_MISMATCH, f.observe().finding)
        }
        assertEquals(0, f.reads)
    }

    @Test fun unknownWithoutUriCanObserveUniqueExactTarget() {
        val f = Fixture()
        f.previous = f.previous.copy(uri = null)
        assertEquals(JpegReconciliationFinding.PENDING_BYTES_MATCH, f.observe().finding)
    }

    @Test fun permissionProviderReadAndCloseFailuresDoNotProduceEvidence() {
        for (fault in listOf("find", "inspect", "open", "read", "close")) {
            val f = Fixture()
            f.fault = fault
            assertThrows(IOException::class.java) { f.observe() }
            assertEquals(fault == "close", f.resources.hasOpenResources)
            f.fault = "none"
            assertNull(f.resources.releaseAll())
            assertFalse(f.resources.hasOpenResources)
        }
    }

    @Test fun failedCloseDuringByteMismatchIsNotNegativeEvidence() {
        val f = Fixture()
        f.bytes = byteArrayOf()
        f.fault = "close"
        assertThrows(DngContentChanged::class.java) { f.observe() }
        assertTrue(f.resources.hasOpenResources)
    }

    @Test fun sourceGenerationRecipeRendererAndRevisionAreFenced() {
        val f = Fixture()
        val render = f.previous.render as CompletedRender
        for (changed in listOf(render.copy(request = render.request.copy(generation = 2)),
            render.copy(request = render.request.copy(source = render.request.source.copy(uri = "content://test/source/2"))),
            render.copy(request = render.request.copy(source = render.request.source.copy(
                fingerprint = render.request.source.fingerprint.copy(sha256 = "b".repeat(64))))),
            render.copy(request = render.request.copy(recipe = RenderRecipeSnapshot("recipe-v1", "changed"))),
            render.copy(rendererContract = "other"))) {
            assertThrows(CaptureJournalConflict::class.java) { f.observe(changed) }
        }
        f.afterRead = { f.durable = f.previous.copy(revision = f.previous.revision + 1) }
        assertThrows(CaptureJournalConflict::class.java) { f.observe() }
        assertFalse(f.resources.hasOpenResources)
    }

    @Test fun generationStopOrMetadataChangeDuringReadPreventsPositiveEvidence() {
        for (change in listOf("generation", "stop", "metadata", "duplicate")) {
            val f = Fixture()
            f.afterRead = {
                when (change) {
                    "generation" -> f.current = false
                    "stop" -> f.stopped = true
                    "metadata" -> f.row = f.row.copy(pending = false)
                    "duplicate" -> f.candidates = { listOf(f.row, f.row) }
                }
            }
            if (change == "generation" || change == "stop") {
                assertThrows(DngVerificationStopped::class.java) { f.observe() }
            } else assertEquals(JpegReconciliationFinding.METADATA_MISMATCH, f.observe().finding)
            assertFalse(f.resources.hasOpenResources)
        }
    }

    @Test fun unsupportedLegacyRendererIsHeldWithoutReadingMedia() {
        val f = Fixture()
        assertThrows(IOException::class.java) { f.observe(contracts = emptySet()) }
        assertEquals(0, f.reads)
        assertArrayEquals(JpegPublicationCodec.encode(f.previous),
            JpegPublicationCodec.encode(JpegPublicationCodec.decode(JpegPublicationCodec.encode(f.previous))))
    }

    @Test fun manualTrialCodecV3RetainsExactUnknownEvidence() {
        val f = Fixture()
        val request = f.previous.render.request.copy(captureId = "TRIAL24_capture-1", recipe = ManualOriginalTrialRender.recipe(.5))
        val trial = ManualOriginalTrialRender(request, OriginalRasterGeometry(2, 2, 1, 2, 2, true),
            f.previous.fingerprint, .5, -.25)
        val target = f.previous.target.copy(captureId = request.captureId)
        val encoded = JpegPublicationCodec.encode(f.previous.copy(target = target, render = trial))
        f.previous = JpegPublicationCodec.decode(encoded)
        f.row = f.row.copy(displayName = target.displayName)
        val result = f.observe(contracts = setOf(ManualOriginalTrialRender.CONTRACT))
        assertEquals(JpegReconciliationFinding.PENDING_BYTES_MATCH, result.finding)
        assertArrayEquals(encoded, JpegPublicationCodec.encode(result.previous))
    }

    private class Fixture : JpegReconciliationRows {
        var bytes = byteArrayOf(1, 2, 3, 4)
        private val fp = OriginalFingerprint(bytes.size.toLong(), MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) })
        private val render = CompletedRender(RenderedJpegRequest("capture-1", 1,
            SharedOriginalReference("content://test/source/1", fp, OriginalGrant.APP_OWNED),
            RenderRecipeSnapshot("recipe-v1", "fixed"), JpegOutputDescription(2, 2, 1, "sRGB", fp.bytes)),
            "renderer-v1", RenderedResultKind.COMPLETE_STAGE_CLEAR,
            RenderedFaceState.VERIFIED_NONE, RenderedExposureState.SUPPORTED)
        var previous = JpegPublicationRecord(JpegPublicationTarget("capture-1", 1, "a".repeat(32)),
            render, fp, 7, JpegPublicationPhase.UNKNOWN, "content://media/external_primary/images/media/10")
        var durable: JpegPublicationRecord? = null
        var row = previous.target.let { DngPendingRow(requireNotNull(previous.uri), it.displayName,
            it.volume, it.relativePath, it.mimeType, true, true) }
        var candidates: () -> List<DngPendingRow> = { listOf(row) }
        val resources = DngIoResources()
        var current = true
        var stopped = false
        var reads = 0
        var fault = "none"
        var afterRead: () -> Unit = {}
        fun observe(expected: JpegPublicationRender = previous.render,
            contracts: Set<RendererPublicationContract> = setOf(RendererPublicationContract("renderer-v1", "recipe-v1"))) =
            JpegReconciliation.observe(previous, expected, contracts, this, resources,
                { durable ?: previous }, { current }, { stopped })
        override fun find(target: JpegPublicationTarget): List<DngPendingRow> {
            if (fault == "find") throw IOException("Synthetic query failure")
            return candidates()
        }
        override fun inspect(uri: String): DngPendingRow {
            if (fault == "inspect") throw IOException("Synthetic metadata failure")
            return row
        }
        override fun openRead(target: JpegPublicationTarget, uri: String, pending: Boolean): ByteArrayInputStream {
            if (fault == "open") throw IOException("Synthetic permission failure")
            reads++
            return object : ByteArrayInputStream(bytes) {
                override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                    if (fault == "read") throw IOException("Synthetic read failure")
                    return super.read(buffer, off, len).also { afterRead() }
                }
                override fun close() {
                    if (fault == "close") throw IOException("Synthetic close failure")
                    super.close()
                }
            }
        }
    }
}
