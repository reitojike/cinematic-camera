package dev.cinematic.p0.metrics

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.rawcapture.SaveAuthorityIntervalExperiment
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** Opt-in only, and preserves each existing fixture's stricter isolation/class/kill gates. */
class AuthorityIntervalTestRule : TestRule {
    override fun apply(base: Statement, description: Description) = object : Statement() {
        override fun evaluate() {
            val args = InstrumentationRegistry.getArguments()
            val raw = args.getString("authorityIntervalMillis") ?: return base.evaluate()
            check(args.getString("isolated") == "true" && Build.HARDWARE in setOf("ranchu", "goldfish"))
            val millis = raw.toLong(); check(millis == 50L || millis == 100L)
            SaveAuthorityIntervalExperiment.configureForTest(millis)
            try {
                check(SaveAuthorityIntervalExperiment.intervalNanos() == millis * 1_000_000)
                base.evaluate()
            } finally { SaveAuthorityIntervalExperiment.configureForTest(null) }
        }
    }
}
