package dev.cinematic.p0.rawcapture

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class DngImportReadGrantsTest {
    private val fingerprint = OriginalFingerprint(4, "a".repeat(64))
    private val source = "content://synthetic/first"
    private class Death : Error()
    private inner class Fixture {
        var bytes: ByteArray? = null
        val journal = DngImportJournal("import-1", object : AtomicJournalStorage {
            override fun read(maxBytes: Int) = bytes?.copyOf()
            override fun replace(contents: ByteArray) { bytes = contents.copyOf() }
        }).also { it.prepare(DngImportRecord(DngImportTarget("import-1", "b".repeat(32)), source, fingerprint, DngImportGrant.SESSION_ONLY)) }
        val held = mutableSetOf<String>()
        var takes = 0; var releases = 0
        var denyTake = false; var dieAfterTake = false; var failRelease = false
        val port = object : DngImportReadGrantPort {
            override fun held(uri: String) = uri in held
            override fun takeRead(uri: String) {
                assertEquals(DngImportGrant.ACQUIRED, journal.read()!!.grant)
                takes++
                if (denyTake) throw SecurityException("denied")
                held += uri
                if (dieAfterTake) throw Death()
            }
            override fun releaseRead(uri: String) {
                releases++
                if (failRelease) throw IOException("release unknown")
                held -= uri
            }
        }
        val grants = DngImportReadGrants(journal, port)
        fun current() = journal.read()!!
    }

    @Test fun noOfferDoesNotAcquireAndDenialKeepsSessionOnly() {
        val f = Fixture()
        assertEquals(DngImportGrant.SESSION_ONLY, f.grants.retain(f.current(), false).grant)
        assertEquals(0, f.takes)
        f.denyTake = true
        assertEquals(DngImportGrant.SESSION_ONLY, f.grants.retain(f.current(), true).grant)
        assertEquals(1, f.takes)
    }
    @Test fun preexistingReadGrantIsNeverReleasedByImport() {
        val f = Fixture(); f.held += source
        val record = f.grants.retain(f.current(), true)
        assertEquals(DngImportGrant.ALREADY_HELD, record.grant)
        f.grants.release(record, false)
        assertEquals(0, f.releases); assertTrue(source in f.held)
    }
    @Test fun acquiredSameUriReselectionPreservesOwnershipUntilRelease() {
        val f = Fixture(); f.grants.retain(f.current(), true)
        val record = f.grants.reselect(f.current(), source, fingerprint, true, false)
        assertEquals(DngImportGrant.ACQUIRED, record.grant); assertEquals(1, f.takes)
        f.grants.release(record, false)
        assertEquals(1, f.releases); assertFalse(source in f.held)
    }
    @Test fun newUriReleasesOldLeaseBeforeLosingItsReference() {
        val f = Fixture(); f.grants.retain(f.current(), true)
        val next = "content://synthetic/second"
        val record = f.grants.reselect(f.current(), next, fingerprint, true, false)
        assertEquals(next, record.sourceUri); assertEquals(setOf(next), f.held)
        assertEquals(1, f.releases); assertEquals(2, f.takes)
    }
    @Test fun failedReleaseDoesNotReplaceSourceOrAcquireAnother() {
        val f = Fixture(); f.grants.retain(f.current(), true); f.failRelease = true
        assertThrows(IOException::class.java) {
            f.grants.reselect(f.current(), "content://synthetic/second", fingerprint, true, false)
        }
        assertEquals(source, f.current().sourceUri); assertEquals(DngImportGrant.ACQUIRED, f.current().grant)
        assertEquals(1, f.takes)
    }
    @Test fun sharedLeaseIsNotReleasedOrRebound() {
        val f = Fixture(); f.grants.retain(f.current(), true)
        f.grants.release(f.current(), true)
        assertThrows(IOException::class.java) { f.grants.reselect(f.current(), "content://synthetic/second", fingerprint, true, true) }
        assertEquals(0, f.releases)
    }
    @Test fun deathAfterAcquisitionRecoversOwnershipFromIntent() {
        val f = Fixture(); f.dieAfterTake = true
        assertThrows(Death::class.java) { f.grants.retain(f.current(), true) }
        val resumed = DngImportReadGrants(f.journal, f.port)
        assertEquals(DngImportGrant.ACQUIRED, resumed.retain(f.current(), true).grant)
        resumed.release(f.current(), false)
        assertTrue(f.held.isEmpty()); assertEquals(1, f.takes)
    }
    @Test fun revokedOwnedGrantCanBeReacquiredWithoutBroadeningPermission() {
        val f = Fixture(); f.grants.retain(f.current(), true); f.held.clear()
        f.grants.retain(f.current(), true)
        assertEquals(2, f.takes); assertEquals(DngImportGrant.ACQUIRED, f.current().grant)
    }
    @Test fun differentMaterialCannotReleaseOrRebind() {
        val f = Fixture(); f.grants.retain(f.current(), true)
        assertThrows(IllegalArgumentException::class.java) {
            f.grants.reselect(f.current(), "content://synthetic/other", OriginalFingerprint(5, "c".repeat(64)), true, false)
        }
        assertEquals(0, f.releases); assertEquals(source, f.current().sourceUri)
    }
}
