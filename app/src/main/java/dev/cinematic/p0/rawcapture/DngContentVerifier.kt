package dev.cinematic.p0.rawcapture

import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/** Observed byte identity, not proof of DNG validity or of pre-observation integrity. */
internal data class OriginalFingerprint(val bytes: Long, val sha256: String) {
    init {
        require(bytes > 0) { "Empty original fingerprint" }
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid original digest" }
    }
}

internal class DngContentChanged : IOException("Original content does not match")
internal class DngVerificationStopped : IOException("Original verification stopped")

/**
 * Synchronous IO for a capture-priority executor, never the UI thread.
 * The opener must be read-only. Owns and closes its stream, even on failure/stop.
 * Stop is cooperative between reads; it cannot interrupt an already blocked provider read.
 */
internal object DngContentVerifier {
    fun identify(
        expectedBytes: Long,
        openReadOnly: () -> InputStream,
        shouldStop: () -> Boolean = { false },
    ): OriginalFingerprint {
        require(expectedBytes > 0) { "Empty original" }
        checkContinue(shouldStop)
        val fingerprint = openReadOnly().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                checkContinue(shouldStop)
                val count = input.read(buffer)
                checkContinue(shouldStop)
                if (count < 0) break
                if (count == 0) throw IOException("Original read made no progress")
                if (count.toLong() > expectedBytes - total) throw DngContentChanged()
                digest.update(buffer, 0, count)
                total += count
            }
            if (total != expectedBytes) throw DngContentChanged()
            OriginalFingerprint(total, digest.digest().joinToString("") { "%02x".format(it) })
        }
        checkContinue(shouldStop) // close returning is part of successful verification.
        return fingerprint
    }

    /** Content equality only; the caller must separately verify URI ownership/pending/publication. */
    fun verify(
        expected: OriginalFingerprint,
        openReadOnly: () -> InputStream,
        shouldStop: () -> Boolean = { false },
    ): OriginalFingerprint {
        val observed = identify(expected.bytes, openReadOnly, shouldStop)
        if (observed != expected) throw DngContentChanged()
        return observed
    }

    private fun checkContinue(shouldStop: () -> Boolean) {
        if (shouldStop()) throw DngVerificationStopped()
    }
}
