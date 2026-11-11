package dev.cinematic.p0.photoroll

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DngInputProbeTest {
    @Test fun summaryContainsClassesAndFirstRepositorySourceOnly() {
        val cause = IllegalArgumentException("private document name").apply {
            stackTrace = arrayOf(
                StackTraceElement("android.content.Provider", "open", "Provider.java", 7),
                StackTraceElement("dev.cinematic.p0.photoroll.PhotoRollRepository", "check", "PhotoRollRepository.kt", 123),
                StackTraceElement("dev.cinematic.p0.photoroll.PhotoRollScreen", "render", "PhotoRollScreen.kt", 456))
        }
        val failure = java.util.concurrent.CompletionException("content://private/document/secret", cause).apply {
            stackTrace = emptyArray()
        }
        val line = DngInputProbe.failureSummary(failure)
        assertEquals("class=java.util.concurrent.CompletionException source=unknown:-1 cause " +
            "class=java.lang.IllegalArgumentException source=PhotoRollRepository.kt:123", line)
        assertFalse(line.contains("private"))
        assertFalse(line.contains("content://"))
        assertFalse(line.contains("PhotoRollScreen"))
    }

    @Test fun causeChainIsBoundedAndSourcePathsAreRedacted() {
        var failure: Throwable = IllegalStateException("private")
        repeat(8) { failure = IllegalStateException("private", failure) }
        failure.stackTrace = arrayOf(StackTraceElement("dev.cinematic.p0.Probe", "read", "/private/path/secret.kt", 9))
        val line = DngInputProbe.failureSummary(failure)
        assertEquals(4, "class=".toRegex().findAll(line).count())
        assertTrue(line.startsWith("class=java.lang.IllegalStateException source=redacted:9"))
        assertFalse(line.contains("private"))
    }

    @Test fun brokenThrowableFormattingDoesNotEscape() {
        val failure = object : RuntimeException() {
            override val cause: Throwable get() = throw IllegalStateException("private formatter failure")
        }
        assertEquals("class=unavailable source=unknown:-1", DngInputProbe.failureSummary(failure))
    }

    @Test fun nonSourceBasenamesAndWindowsPathsAreRedacted() {
        for (name in listOf("secret.dng", "C:\\private\\Secret.kt", "../Secret.java", "secret photo.kt")) {
            val failure = IllegalStateException().apply {
                stackTrace = arrayOf(StackTraceElement("dev.cinematic.p0.Probe", "read", name, 1))
            }
            assertEquals("class=java.lang.IllegalStateException source=redacted:1", DngInputProbe.failureSummary(failure))
        }
    }
}
