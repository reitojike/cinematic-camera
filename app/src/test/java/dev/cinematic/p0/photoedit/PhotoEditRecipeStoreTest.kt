package dev.cinematic.p0.photoedit

import dev.cinematic.p0.photoroll.TrialStrengthEditor
import dev.cinematic.p0.rawcapture.*
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class PhotoEditRecipeStoreTest {
    private val source = SharedOriginalReference("content://synthetic.test/images/1",
        OriginalFingerprint(12, "a".repeat(64)), OriginalGrant.USER_SELECTED)
    private val values = PhotoEditValues(.7, 22.0, -.4)
    private class Memory : AtomicJournalStorage {
        var bytes: ByteArray? = null
        var failBefore = false
        var failAfter = false
        var corruptReadback = false
        override fun read(maxBytes: Int): ByteArray? = bytes?.copyOf()?.also { if (corruptReadback) it[0] = 0 }
        override fun replace(contents: ByteArray) {
            if (failBefore) throw IOException("injected before commit")
            bytes = contents.copyOf()
            if (failAfter) throw IOException("injected uncertain commit")
        }
    }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected IOException") } catch (_: IOException) { }
    }
    @Test fun absentRecordDoesNotInventEdits() {
        assertNull(PhotoEditRecipeStore(source, Memory()).read())
    }
    @Test fun freshOwnerRestoresExactValuesAndCurrentRecipe() {
        val storage = Memory()
        PhotoEditRecipeStore(source, storage).save(values)
        assertEquals(values, PhotoEditRecipeStore(source, storage).read())
    }
    @Test fun sourceUriContentLengthAndGrantAreAllBound() {
        val bytes = PhotoEditRecipeCodec.encode(source, values)
        listOf(source.copy(uri = "content://synthetic.test/images/2"),
            source.copy(fingerprint = source.fingerprint.copy(bytes = 13)),
            source.copy(fingerprint = source.fingerprint.copy(sha256 = "b".repeat(64))),
            source.copy(grant = OriginalGrant.APP_OWNED)).forEach {
            rejects { PhotoEditRecipeCodec.decode(it, bytes) }
        }
    }
    @Test fun truncatedUnknownOversizedAndTrailingRecordsAreRejected() {
        val bytes = PhotoEditRecipeCodec.encode(source, values)
        for (length in bytes.indices) rejects { PhotoEditRecipeCodec.decode(source, bytes.copyOf(length)) }
        rejects { PhotoEditRecipeCodec.decode(source, bytes + byteArrayOf(0)) }
        rejects { PhotoEditRecipeCodec.decode(source, ByteArray(PhotoEditRecipeCodec.MAX_BYTES + 1)) }
        rejects { PhotoEditRecipeCodec.decode(source, bytes.copyOf().also { it[7] = 2 }) }
    }
    @Test fun unknownProcessingRecipeCannotBeReinterpreted() {
        val bytes = PhotoEditRecipeCodec.encode(source, values)
        val text = bytes.toString(Charsets.ISO_8859_1).replace("profile=preliminary", "profile=unsupported")
        rejects { PhotoEditRecipeCodec.decode(source, text.toByteArray(Charsets.ISO_8859_1)) }
    }
    @Test fun invalidOrMissingCoordinatesCannotBeSaved() {
        listOf(PhotoEditValues(Double.NaN, 0.0, 0.0), PhotoEditValues(.5),
            PhotoEditValues(.5, 101.0, 0.0), PhotoEditValues(.5, 0.0, -2.1)).forEach {
            try { PhotoEditRecipeCodec.encode(source, it); fail("Expected invalid values") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun failedWritePreservesPreviousValuesAndRetryIsIdempotent() {
        val storage = Memory()
        val store = PhotoEditRecipeStore(source, storage)
        store.save(values)
        storage.failBefore = true
        rejects { store.save(PhotoAdjustmentPolicy.defaults) }
        assertEquals(values, store.read())
        storage.failBefore = false
        store.save(PhotoAdjustmentPolicy.defaults)
        store.save(PhotoAdjustmentPolicy.defaults)
        assertEquals(PhotoAdjustmentPolicy.defaults, store.read())
    }
    @Test fun uncertainCommitIsNotReportedSuccessfulButCanBeReconciled() {
        val storage = Memory().apply { failAfter = true }
        val store = PhotoEditRecipeStore(source, storage)
        rejects { store.save(values) }
        storage.failAfter = false
        assertEquals(values, store.read())
        storage.corruptReadback = true
        rejects { store.save(values) }
        storage.corruptReadback = false
        store.save(values)
        assertEquals(values, store.read())
    }
    @Test fun restoredEditorNeedsDisplayAndCancelDoesNotReplacePersistedValues() {
        val storage = Memory()
        val store = PhotoEditRecipeStore(source, storage)
        store.save(values)
        var editor = TrialStrengthEditor.empty(requireNotNull(store.read()))
        editor = requireNotNull(editor.bind(source))
        assertNull(editor.begin(.01, true).session)
        editor = editor.shown(editor.ticket, values).begin(.01, true)
        val token = editor.session!!.inputToken
        val changed = editor.change(token, PhotoEditParameter.STRENGTH, .2).apply(token)
        assertSame(changed, changed.close(token)) // Not displayed yet.
        assertEquals(values, changed.cancel(token).requestedValues)
        assertEquals(values, store.read())
        val shown = changed.shown(changed.ticket, changed.requestedValues)
        val accepted = shown.close(token)
        assertNull(accepted.session)
        store.save(accepted.requestedValues)
        assertEquals(.2, store.read()!!.strength, 0.0)
    }
    @Test fun savedScreenValuesRequireSameOriginalAndProcessingContract() {
        val id = PhotoEditRestoration.identity(source)
        val contract = PhotoEditRestoration.contract()
        PhotoEditRestoration.requireMatches(source, id, contract)
        rejects { PhotoEditRestoration.requireMatches(source.copy(fingerprint = source.fingerprint.copy(bytes = 13)), id, contract) }
        rejects { PhotoEditRestoration.requireMatches(source.copy(grant = OriginalGrant.APP_OWNED), id, contract) }
        rejects { PhotoEditRestoration.requireMatches(source, id, "old-version") }
        rejects { PhotoEditRestoration.requireMatches(source, null, contract) }
    }
    @Test fun recreatedOwnerCannotLetDelayedOldCloseOverwriteNewClose() {
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val memory = Memory()
        val blocked = object : AtomicJournalStorage {
            override fun read(maxBytes: Int) = memory.read(maxBytes)
            override fun replace(contents: ByteArray) {
                entered.countDown()
                check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                memory.replace(contents)
            }
        }
        try {
            val oldOwner = PhotoEditRecipeQueue(executor) { blocked }
            val oldWrite = oldOwner.save(source, values)
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val newOwner = PhotoEditRecipeQueue(executor) { memory }
            val newValues = PhotoEditValues(.2, -30.0, 1.0)
            val newWrite = newOwner.save(source, newValues)
            val read = newOwner.read(source)
            release.countDown()
            oldWrite.get(5, java.util.concurrent.TimeUnit.SECONDS)
            newWrite.get(5, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(newValues, read.get(5, java.util.concurrent.TimeUnit.SECONDS))
        } finally { release.countDown(); executor.shutdownNow() }
    }

}
