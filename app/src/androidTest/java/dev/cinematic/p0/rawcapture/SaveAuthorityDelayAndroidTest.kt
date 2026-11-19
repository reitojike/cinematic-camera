package dev.cinematic.p0.rawcapture

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** Isolated durable-byte mutation model for checkpoint scheduling, not a real-photo save. */
class SaveAuthorityDelayAndroidTest {
    @Test fun reportMutationToEligibleCheckpointAndIoCompletionSeparately() {
        check(Build.HARDWARE in setOf("ranchu", "goldfish"))
        check(InstrumentationRegistry.getArguments().getString("authorityInterval") == "true")
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val output = File(app.getExternalFilesDir(null), "authority-delay").also { check(it.mkdirs()) }
        val rows = mutableListOf("intervalMs\tioDelayMs\tmutationToEligibleNanos\teligibleToCompletionNanos\tmaxWaitNanos\tmaxCheckNanos")
        for (ms in listOf(0L, 50L, 100L)) for (ioDelay in listOf(0L, 20L)) repeat(5) { index ->
            val file = File(app.cacheDir, "authority-delay-$ms-$ioDelay-$index")
            file.writeText("initial")
            var failEntered = 0L
            val window = SaveAuthorityWindow(ms * 1_000_000) {
                val entered = System.nanoTime()
                val same = file.readText() == "initial"
                if (!same) { failEntered = entered; if (ioDelay > 0) Thread.sleep(ioDelay) }
                same
            }
            assertTrue(window.check(false))
            file.outputStream().use { it.write("changed".toByteArray()); it.fd.sync() }
            val changed = System.nanoTime()
            var deadline = System.nanoTime() + 2_000_000_000
            var lastCheckpoint = 0L
            while (true) {
                lastCheckpoint = System.nanoTime()
                if (!window.check(false)) break
                check(System.nanoTime() < deadline)
                Thread.sleep(1) // Test cadence only; no production timer/sleep.
            }
            val detected = System.nanoTime()
            assertTrue(failEntered >= changed)
            assertFalse(window.check(true)) // No failure-to-success reuse.
            val stats = window.stats()
            rows += "$ms\t$ioDelay\t${lastCheckpoint - changed}\t${detected - lastCheckpoint}\t${stats.maxWaitNanos}\t${stats.maxCheckNanos}"
            check(file.delete())
        }
        File(output, "delays.tsv").writeText(rows.joinToString("\n", postfix = "\n"))
    }
}
