package dev.cinematic.p0.photoroll

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.rawcapture.AutoDevelopConditions
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Dedicated empty emulator only. Fake save work never reads/writes an image or public row.
 * Source/intent/publication correctness is covered by the existing save-owner suites.
 */
@RunWith(AndroidJUnit4::class)
class ForegroundExportServiceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun safe() = AutoDevelopConditions(true, false, false, false, true, true, true, true)
    private class Work : ForegroundExportWork {
        override val identity = Any()
        override fun reserveForeground(conditions: AutoDevelopConditions) = true
        @Volatile override var resourcesClosed = true
        val started = CountDownLatch(1)
        val ended = CountDownLatch(1)
        val stopped = CountDownLatch(1)
        val result = CompletableFuture<Unit>()
        @Volatile var failure: Throwable? = null
        override fun advance(conditions: () -> AutoDevelopConditions, stop: () -> Boolean): CompletableFuture<Unit> {
            resourcesClosed = false
            started.countDown()
            return result
        }
        override fun observe(conditions: AutoDevelopConditions) = Unit
        override fun requestStop() { stopped.countDown() }
        override fun finished(failure: Throwable?) { this.failure = failure; ended.countDown() }
        fun closeAndComplete() { resourcesClosed = true; result.complete(Unit) }
    }
    private fun withForeground(block: (ActivityScenario<PhotoRollActivity>) -> Unit) {
        var cameraOwner: PhotoRollCameraOwners.Owner? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            cameraOwner = AndroidPhotoRollCameraObservations.register { PhotoRollCameraSnapshot(false, false, false, false) }
        }
        try { ActivityScenario.launch(PhotoRollActivity::class.java).use(block) }
        finally { InstrumentationRegistry.getInstrumentation().runOnMainSync { cameraOwner?.close() } }
    }
    private fun begin(work: Work) = InstrumentationRegistry.getInstrumentation().runOnMainSync {
        assertTrue(ForegroundExports.start(context, work, safe()))
    }
    private fun stopAndClose(work: Work) {
        ForegroundExports.cancel(work)
        work.closeAndComplete()
        check(work.ended.await(10, TimeUnit.SECONDS))
    }

    @Test fun notificationDeniedStillRunsAndActivityStopDoesNotCancelTheOwner() = withForeground { scenario ->
        if (Build.VERSION.SDK_INT >= 33) assertEquals("Use a fresh emulator with notification permission denied",
            PackageManager.PERMISSION_DENIED, context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS))
        val work = Work()
        try {
            begin(work)
            assertTrue(work.started.await(10, TimeUnit.SECONDS))
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            assertEquals(1L, work.stopped.count)
            assertNotNull(ForegroundExports.gate.active())
            work.closeAndComplete()
            assertTrue(work.ended.await(10, TimeUnit.SECONDS))
            assertNull(work.failure)
        } finally { stopAndClose(work) }
    }

    @Test fun cancelDeliveryWaitsForClosureAndNextSaveCanStart() = withForeground {
        val first = Work()
        try {
            begin(first)
            assertTrue(first.started.await(10, TimeUnit.SECONDS))
            val token = requireNotNull(ForegroundExports.gate.active()).token
            context.startService(Intent(context, ForegroundExportService::class.java)
                .setAction(ForegroundExportService.CANCEL).putExtra(ForegroundExportService.TOKEN, token))
            assertTrue(first.stopped.await(10, TimeUnit.SECONDS))
            assertNotNull(ForegroundExports.gate.active())
            assertFalse(ForegroundExports.start(context, Work(), safe()))
            first.closeAndComplete()
            assertTrue(first.ended.await(10, TimeUnit.SECONDS))
            val next = Work()
            try { begin(next); assertTrue(next.started.await(10, TimeUnit.SECONDS)) }
            finally { stopAndClose(next) }
        } finally { stopAndClose(first) }
    }

    @Test fun failedWorkReleasesOnlyAfterActualClosure() = withForeground {
        val work = Work()
        try {
            begin(work)
            assertTrue(work.started.await(10, TimeUnit.SECONDS))
            work.resourcesClosed = true
            work.result.completeExceptionally(IOException("synthetic failure"))
            assertTrue(work.ended.await(10, TimeUnit.SECONDS))
            assertNotNull(work.failure)
            assertNull(ForegroundExports.gate.active())
        } finally { stopAndClose(work) }
    }

    @Test fun completedFutureWithUnclosedProducerRetainsHoldAndRejectsNextSave() = withForeground {
        val work = Work()
        try {
            begin(work)
            assertTrue(work.started.await(10, TimeUnit.SECONDS))
            // A returned future is not evidence that the producer's retained workers terminated.
            work.result.completeExceptionally(IOException("synthetic worker termination unconfirmed"))
            assertTrue(work.ended.await(10, TimeUnit.SECONDS))
            val held = requireNotNull(ForegroundExports.gate.active())
            assertTrue(held.held)
            assertSame(work, held.value)
            assertFalse(ForegroundExports.start(context, Work(), safe()))
            work.resourcesClosed = true
            assertSame(held, ForegroundExports.gate.active()) // No automatic restart/release on a later flag change.
        } finally {
            stopAndClose(work)
            // Fixture teardown only: the fake producer has positively closed. Do not clear other work.
            ForegroundExports.gate.active()?.takeIf { it.value === work }?.let {
                check(work.resourcesClosed)
                check(ForegroundExports.gate.finish(it, true))
                ForegroundExports.changed()
            }
        }
    }

    @Test fun stoppedServiceKeepsOwnershipUntilLateWorkerCompletion() = withForeground {
        val work = Work()
        try {
            begin(work)
            assertTrue(work.started.await(10, TimeUnit.SECONDS))
            assertTrue(context.stopService(Intent(context, ForegroundExportService::class.java)))
            assertTrue(work.stopped.await(10, TimeUnit.SECONDS))
            assertNotNull(ForegroundExports.gate.active())
            assertFalse(ForegroundExports.start(context, Work(), safe()))
            work.closeAndComplete()
            assertTrue(work.ended.await(10, TimeUnit.SECONDS))
            assertNull(ForegroundExports.gate.active())
        } finally { stopAndClose(work) }
    }
}
