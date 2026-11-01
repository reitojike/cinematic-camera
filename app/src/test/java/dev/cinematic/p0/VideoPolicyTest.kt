package dev.cinematic.p0

import org.junit.Assert.*
import org.junit.Test

class VideoPolicyTest {
    private fun choice(key: String, rank: Int, lower: Int, upper: Int, range: String = "SDR") =
        VideoChoice(key, rank, range, lower, upper, key)

    @Test fun initialSelectionPrioritizesResolutionThenAdvertisedFpsAndFixedRange() {
        val choices = listOf(choice("FHD120", 3, 120, 120), choice("UHD30", 4, 30, 30),
            choice("UHDvariable", 4, 30, 60), choice("UHD60", 4, 60, 60))
        assertEquals(listOf("UHD60", "UHDvariable", "UHD30", "FHD120"), VideoPolicy.ordered(choices, null).map { it.key })
    }

    @Test fun savedUserChoiceIncludingSupportedHdrWinsOverDefaults() {
        val choices = listOf(choice("UHD60", 4, 60, 60), choice("userHDR30", 3, 30, 30, "HLG10"))
        assertEquals("userHDR30", VideoPolicy.ordered(choices, "userHDR30").first().key)
    }

    @Test fun unavailablePreferenceFallsBackToSdrWithoutSelectingHdrOrChangingRequest() {
        val request = "previous-camera-HDR60"
        val choices = listOf(choice("HDR", 4, 60, 60, "HLG10"), choice("SDR", 3, 30, 30))
        assertEquals(listOf("SDR"), VideoPolicy.ordered(choices, request).map { it.key })
        assertTrue(VideoPolicy.fallbackReason(request, "SDR").contains("retained"))
        assertEquals("previous-camera-HDR60", request)
        assertTrue(VideoPolicy.ordered(listOf(choices.first()), null).isEmpty())
    }

    @Test fun stopDuringStartCannotBeRevertedByLateStartAndNextRecordWaitsForPublication() {
        val gate = RecordingGate()
        gate.begin("first")
        assertTrue(gate.stop())
        gate.started("first")
        assertEquals(RecordingStage.STOPPING, gate.stage)
        assertThrows(IllegalStateException::class.java) { gate.begin("second") }
        assertTrue(gate.finalize("first"))
        assertEquals(RecordingStage.FINALIZING, gate.stage)
        assertThrows(IllegalStateException::class.java) { gate.begin("second") }
        gate.complete("first")
        gate.begin("second")
        assertEquals(RecordingStage.STARTING, gate.stage)
    }

    @Test fun staleOrDuplicateFinalizationCannotResetNewRecording() {
        val gate = RecordingGate()
        gate.begin("old"); gate.started("old"); gate.finalize("old"); gate.complete("old")
        gate.begin("new")
        gate.started("old"); assertFalse(gate.finalize("old")); gate.complete("old")
        assertEquals("new", gate.token)
        assertTrue(gate.finalize("new"))
        assertFalse(gate.finalize("new"))
        assertFalse(gate.stop())
        assertEquals(RecordingStage.FINALIZING, gate.stage)
    }

    @Test fun audioFailurePersistsEvenWhenFinalizeIsHealthyAndAudioTrackExists() {
        val notes = AudioQualityNotes(true)
        notes.observe(false, null, false)
        notes.observe(true, "encoder stopped", false)
        notes.observe(false, null, false)
        assertTrue(notes.warning(true).contains("encoder stopped"))
        assertFalse(notes.warning(true).contains("no audio track"))
    }

    @Test fun silencingAndMissingTrackAreExplicitWhileAudioOffIsIntentional() {
        val notes = AudioQualityNotes(true)
        notes.observe(false, null, true)
        assertTrue(notes.warning(true).contains("silenced"))
        assertTrue(notes.warning(false).contains("no audio track"))
        val disabled = AudioQualityNotes(false)
        disabled.observe(true, "ignored disabled state", true)
        assertEquals("", disabled.warning(false))
    }
}
