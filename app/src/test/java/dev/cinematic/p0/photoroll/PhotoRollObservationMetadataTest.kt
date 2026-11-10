package dev.cinematic.p0.photoroll

import dev.cinematic.p0.rawcapture.AutoDevelopConditions
import org.junit.Assert.*
import org.junit.Test

class PhotoRollObservationMetadataTest {
    private val ready = AutoDevelopConditions(true, false, false, false, true, true, true, false, true)
    private val ids = setOf("first", "second")
    private val allAvailable = mapOf("first" to true, "second" to true)

    private fun metadata(sampler: PhotoRollCameraObservationSampler, sample: PhotoRollCameraObservationSampler.Sample,
        conditions: AutoDevelopConditions = ready, generation: Long = sample.generation,
        epoch: Long = 1, resourceObserved: Boolean = true) =
        PhotoRollObservationMetadata(conditions, generation, epoch, resourceObserved,
            sample.complete, sample.unavailable, sampler.consistent(sample, generation))

    @Test fun noRegistrationOrKnownIdsIsNotFirstObservation() {
        val sampler = PhotoRollCameraObservationSampler()
        for (sample in listOf(sampler.capture(1, false, ids, allAvailable),
            sampler.capture(1, true, null, emptyMap()), sampler.capture(1, true, emptySet(), emptyMap()))) {
            val result = metadata(sampler, sample, ready.copy(cameraSessionInUse = true))
            assertFalse(result.initialCameraCallbacksComplete)
            assertFalse(result.cameraUnavailableObserved)
            assertEquals(PhotoRollObservedCamera.UNKNOWN, result.cameraState)
        }
    }

    @Test fun incompleteCallbacksDoNotTurnLegacyFalseIdleIntoObservedUse() {
        val sampler = PhotoRollCameraObservationSampler()
        val sample = sampler.capture(1, true, ids, mapOf("first" to true))
        // Existing snapshot: known.all { available[it] == true } is false for missing "second".
        val result = metadata(sampler, sample, ready.copy(cameraSessionInUse = true))
        assertTrue(result.cameraSampleConsistent)
        assertFalse(result.initialCameraCallbacksComplete)
        assertEquals(PhotoRollObservedCamera.UNKNOWN, result.cameraState)
    }

    @Test fun anExplicitUnavailableCallbackIsObservedUseEvenBeforeAllCallbacks() {
        val sampler = PhotoRollCameraObservationSampler()
        val sample = sampler.capture(1, true, ids, mapOf("first" to false))
        val result = metadata(sampler, sample, ready.copy(cameraSessionInUse = true))
        assertFalse(result.initialCameraCallbacksComplete)
        assertTrue(result.cameraUnavailableObserved)
        assertEquals(PhotoRollObservedCamera.IN_USE, result.cameraState)
    }

    @Test fun allAvailableWithAppIdleIsAvailableButAppUnknownRemainsUnknown() {
        val sampler = PhotoRollCameraObservationSampler()
        val sample = sampler.capture(1, true, ids, allAvailable)
        assertEquals(PhotoRollObservedCamera.AVAILABLE, metadata(sampler, sample).cameraState)
        assertEquals(PhotoRollObservedCamera.UNKNOWN,
            metadata(sampler, sample, ready.copy(cameraSessionInUse = null)).cameraState)
    }

    @Test fun coherentAllAvailableAndBusyConditionMeansObservedAppCameraWork() {
        val sampler = PhotoRollCameraObservationSampler()
        val sample = sampler.capture(1, true, ids, allAvailable)
        // With hardware confirmed idle, the unchanged conditions mapper sets true only for app work.
        assertEquals(PhotoRollObservedCamera.IN_USE,
            metadata(sampler, sample, ready.copy(cameraSessionInUse = true)).cameraState)
    }

    @Test fun callbackBetweenSnapshotAndPublicationMakesMetadataUnknown() {
        val sampler = PhotoRollCameraObservationSampler()
        val available = mutableMapOf("first" to true)
        val sample = sampler.capture(1, true, ids, available)
        val oldConditions = ready.copy(cameraSessionInUse = true)
        sampler.change { available["second"] = true }
        val result = metadata(sampler, sample, oldConditions)
        assertFalse(result.cameraSampleConsistent)
        assertEquals(PhotoRollObservedCamera.UNKNOWN, result.cameraState)
        assertEquals(oldConditions, result.conditions) // Metadata does not repair CPU conditions.
    }

    @Test fun callbackDuringCaptureAndAnyLaterChangeInvalidatesTheSample() {
        val sampler = PhotoRollCameraObservationSampler()
        lateinit var duringWrite: PhotoRollCameraObservationSampler.Sample
        sampler.change { duringWrite = sampler.capture(1, true, ids, allAvailable) }
        assertFalse(sampler.consistent(duringWrite, 1))
        val beforeWrite = sampler.capture(1, true, ids, allAvailable)
        sampler.change { }
        assertFalse(sampler.consistent(beforeWrite, 1))
    }

    @Test fun repeatedEqualCallbacksDoNotMakeDifferentPublishedMetadata() {
        val sampler = PhotoRollCameraObservationSampler()
        val available = allAvailable.toMutableMap()
        val before = metadata(sampler, sampler.capture(1, true, ids, available))
        sampler.change { available["first"] = true }
        val after = metadata(sampler, sampler.capture(1, true, ids, available))
        assertEquals(before, after) // No revision counter in StateFlow's public value.
    }

    @Test fun registrationGenerationInvalidatesOldSamplesAndNewRegistrationStartsUnknown() {
        val sampler = PhotoRollCameraObservationSampler()
        val old = sampler.capture(1, true, ids, allAvailable)
        assertFalse(sampler.consistent(old, 2))
        assertEquals(PhotoRollObservedCamera.UNKNOWN, metadata(sampler, old, generation = 2).cameraState)
        val fresh = sampler.capture(2, true, ids, emptyMap())
        assertFalse(fresh.complete)
    }

    @Test fun pauseResumeKeepsRegistrationCompletenessButNeedsNewForegroundPublication() {
        val sampler = PhotoRollCameraObservationSampler()
        val sample = sampler.capture(1, true, ids, allAvailable)
        val reset = metadata(sampler, sample, AutoDevelopConditions(foreground = true), epoch = 3, resourceObserved = false)
        assertTrue(reset.initialCameraCallbacksComplete)
        assertEquals(1L, reset.registrationGeneration)
        assertEquals(PhotoRollObservedCamera.UNKNOWN, reset.cameraState)
        val observed = metadata(sampler, sample, epoch = 3)
        assertEquals(PhotoRollObservedCamera.AVAILABLE, observed.cameraState)
    }

    @Test fun inconsistentAvailableConditionsAndUnavailableMetadataStayUnknown() {
        val sampler = PhotoRollCameraObservationSampler()
        val sample = sampler.capture(1, true, ids, mapOf("first" to false, "second" to true))
        assertEquals(PhotoRollObservedCamera.UNKNOWN, metadata(sampler, sample).cameraState)
    }

    @Test fun nullListenerReturnsOriginalPublisherAndNeverReadsMetadata() {
        var calls = 0
        val publish: (AutoDevelopConditions) -> Unit = { calls++ }
        val wrapped = photoRollPublicationWithMetadata(publish, null) { error("CPU must not read metadata") }
        assertSame(publish, wrapped)
        wrapped(ready)
        assertEquals(1, calls)
    }

    @Test fun listenerRunsAfterOriginalPublishAndCannotChangeItsOutcome() {
        val order = mutableListOf<String>()
        val sampler = PhotoRollCameraObservationSampler()
        val sample = sampler.capture(1, true, ids, allAvailable)
        val publish = photoRollPublicationWithMetadata({ assertSame(ready, it); order += "conditions" },
            { order += "metadata"; throw AssertionError("optional observer failed") }) {
            order += "capture"
            metadata(sampler, sample, it)
        }
        publish(ready)
        assertEquals(listOf("conditions", "capture", "metadata"), order)
    }

    @Test fun originalPublicationFailureIsUnchangedAndDoesNotNotifyMetadata() {
        val original = IllegalStateException("original publisher failure")
        var notifications = 0
        val publish = photoRollPublicationWithMetadata({ throw original }, { notifications++ }) {
            error("Cannot observe a failed original publication")
        }
        try { publish(ready); fail("Original failure must propagate") }
        catch (caught: IllegalStateException) { assertSame(original, caught) }
        assertEquals(0, notifications)
    }

    @Test fun oldOwnerRejectionHasNoExtraMetadataOrConditionPublication() {
        val authority = PhotoRollObservationAuthority()
        val old = Any(); val current = Any()
        val conditions = mutableListOf<AutoDevelopConditions>()
        val observations = mutableListOf<PhotoRollObservationMetadata>()
        val sampler = PhotoRollCameraObservationSampler()
        val sample = sampler.capture(1, true, ids, allAvailable)
        val publish = photoRollPublicationWithMetadata({ conditions += it }, { observations += it }) {
            metadata(sampler, sample, it)
        }
        authority.foreground(old, true, publish)
        authority.foreground(current, true, publish)
        authority.observed(current, ready, publish)
        authority.foreground(old, false, publish)
        authority.observed(old, ready.copy(cameraSessionInUse = true), publish)
        assertEquals(3, conditions.size)
        assertEquals(conditions, observations.map { it.conditions })
    }

    @Test fun staleMetadataCanBeDroppedWithoutChangingOriginalPublication() {
        var originalCalls = 0
        var metadataCalls = 0
        val publish = photoRollPublicationWithMetadata({ originalCalls++ }, { metadataCalls++ }) { null }
        publish(ready)
        assertEquals(1, originalCalls)
        assertEquals(0, metadataCalls)
    }
}
