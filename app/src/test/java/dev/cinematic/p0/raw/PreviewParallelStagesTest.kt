package dev.cinematic.p0.raw

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class PreviewParallelStagesTest {
    private fun image(w: Int, h: Int) = LinearSrgbImage(w, h, FloatArray(w*h*3) { i ->
        when(i % 11) { 0 -> -0.001f; 1 -> 2f; else -> ((i * 37 % 1009) / 1008f) }
    })
    private fun equal(a: LinearSrgbImage, b: LinearSrgbImage) {
        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
        assertArrayEquals(a.copyPixels().map { it.toRawBits() }.toIntArray(),
            b.copyPixels().map { it.toRawBits() }.toIntArray())
    }

    @Test fun syntheticStagesMatchSerialBits() {
        for ((w,h) in listOf(1 to 1, 7 to 1, 1 to 9, 17 to 13, 64 to 48)) {
            val input = image(w,h)
            val skin = FloatPlane(w,h,FloatArray(w*h) { (it % 5) / 4f })
            for (strength in listOf(0.0,0.5,1.0)) {
                val mist = StageClearMist.Result(input,strength)
                for (workers in 1..2) PreviewPixelExecution(workers).use { execution ->
                    equal(R7Base.foundation(input,skin,strength-0.5), R7Base.foundation(input,skin,strength-0.5,execution))
                    equal(StageClearOrton.render(mist,skin), StageClearOrton.render(mist,skin,execution))
                    equal(StageClearCharacter.render(input,strength), StageClearCharacter.render(input,strength,execution))
                }
            }
        }
    }

    @Test fun combinedThreeStagesAt1024MatchBits() {
        val input=image(1024,683)
        val skin=FloatPlane(1024,683,FloatArray(1024*683) { (it%7)/6f })
        val base=R7Base.foundation(input,skin,0.0)
        val baseline=StageClearCharacter.render(StageClearOrton.render(StageClearMist.Result(base,.5),skin),.5)
        PreviewPixelExecution(2).use { execution ->
            val candidateBase=R7Base.foundation(input,skin,0.0,execution)
            val candidate=StageClearCharacter.render(StageClearOrton.render(StageClearMist.Result(candidateBase,.5),skin,execution),.5,execution)
            equal(baseline,candidate)
        }
    }

    @Test fun cancellationClosesWorkersAndRetrySucceeds() {
        repeat(3) {
            val stop=AtomicBoolean(false); val active=AtomicInteger()
            val execution=PreviewPixelExecution(2) { stop.get() }
            try {
                execution.use { it.ranges(100) { _, _ ->
                    active.incrementAndGet()
                    try { stop.set(true); it.checkpoint() } finally { active.decrementAndGet() }
                } }
                fail("expected cancellation")
            } catch (_: CancellationException) { }
            assertEquals(0,active.get())
            PreviewPixelExecution(2).use { retry -> retry.ranges(8) { _, _ -> retry.checkpoint() } }
        }
    }

    @Test fun workerFailureStopsSiblingBeforeReturning() {
        val started=CountDownLatch(2); val active=AtomicInteger()
        val execution=PreviewPixelExecution(2)
        try {
            execution.use { it.ranges(2) { start, _ ->
                active.incrementAndGet()
                try {
                    started.countDown(); check(started.await(5,TimeUnit.SECONDS))
                    if(start==0) error("injected worker failure")
                    while(true) it.checkpoint()
                } finally { active.decrementAndGet() }
            } }
            fail("expected failure")
        } catch (expected: IllegalStateException) { assertEquals("injected worker failure",expected.message) }

        assertEquals(0,active.get())
    }

    @Test fun invalidWorkerCountRejected() {
        for (workers in listOf(0,3)) try { PreviewPixelExecution(workers); fail() } catch (_: IllegalArgumentException) { }
    }
    @Test fun hostReferenceBenchmark() {
        org.junit.Assume.assumeTrue(System.getenv("PREVIEW_WORKER_BENCHMARK") == "1")
        val input=image(1024,683)
        val skin=FloatPlane(1024,683,FloatArray(1024*683) { (it%7)/6f })
        val cases=listOf<Pair<String,(PreviewPixelExecution?) -> LinearSrgbImage>>(
            "foundation" to { execution -> R7Base.foundation(input,skin,0.0,execution) },
            "orton" to { execution -> StageClearOrton.render(StageClearMist.Result(input,.5),skin,execution) },
            "character" to { execution -> StageClearCharacter.render(input,.5,execution) },
        )
        for ((name, render) in cases) {
            val baseline=render(null)
            repeat(2) { render(null); PreviewPixelExecution(2).use { render(it) } }
            val times=mutableMapOf(1 to mutableListOf<Double>(),2 to mutableListOf<Double>())
            for(workers in listOf(1,2,2,1,1,2,2,1)) {
                val start=System.nanoTime()
                val result=if(workers==1) render(null) else PreviewPixelExecution(2).use { render(it) }
                val elapsed=(System.nanoTime()-start)/1_000_000.0
                equal(baseline,result)
                times.getValue(workers).add(elapsed)
            }
            for((workers, samples) in times) {
                val sorted=samples.sorted();val median=(sorted[1]+sorted[2])/2
                println("REFERENCE_ONLY stage=$name size=1024x683 workers=$workers samples_ms=$samples median_ms=$median")
            }
        }
    }

    @Test fun cancellationInsideEachStageDiscardsResult() {
        val input=image(64,48)
        val skin=FloatPlane(64,48,FloatArray(64*48))
        val stages=listOf<(PreviewPixelExecution)->LinearSrgbImage>(
            { R7Base.foundation(input,skin,0.0,it) },
            { StageClearOrton.render(StageClearMist.Result(input,.5),skin,it) },
            { StageClearCharacter.render(input,.5,it) },
        )
        for (stage in stages) {
            val checks=AtomicInteger()
            try {
                PreviewPixelExecution(2) { checks.incrementAndGet() >= 5 }.use { stage(it) }
                fail("expected cancellation inside stage")
            } catch (_: CancellationException) { }
            PreviewPixelExecution(2).use { stage(it) }
        }
    }

    @Test fun stoppedZeroOrtonAndClosedContextAreRejected() {
        val input=image(1,1);val skin=FloatPlane(1,1,floatArrayOf(0f))
        try {
            PreviewPixelExecution(2) { true }.use { StageClearOrton.render(StageClearMist.Result(input,0.0),skin,it) }
            fail("stopped zero-strength operation must not publish")
        } catch (_: CancellationException) { }
        val closed=PreviewPixelExecution(2);closed.close()
        try { closed.ranges(1) { _, _ -> };fail() } catch (_: IllegalStateException) { }
    }

    @Test fun callerInterruptionIsRestoredAndWorkersJoined() {
        val active=AtomicInteger();val started=CountDownLatch(1);val restored=AtomicBoolean(false)
        val caller=Thread {
            try {
                PreviewPixelExecution(2).use { execution -> execution.ranges(2) { _, _ ->
                    active.incrementAndGet()
                    try { started.countDown();while(true) execution.checkpoint() }
                    finally { active.decrementAndGet() }
                } }
                fail("expected interrupted caller")
            } catch (_: InterruptedException) { restored.set(Thread.currentThread().isInterrupted) }
        }
        caller.start();assertTrue(started.await(5,TimeUnit.SECONDS));caller.interrupt();caller.join(5000)
        assertFalse(caller.isAlive);assertTrue(restored.get());assertEquals(0,active.get())
    }

}
