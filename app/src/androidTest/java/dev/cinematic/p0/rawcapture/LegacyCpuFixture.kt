package dev.cinematic.p0.rawcapture

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.BuildConfig
import dev.cinematic.p0.jpegrender.JpegGenerationRecipe
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Test-only legacy CPU contract harness. Never attaches to DevelopExecution or obtains the
 * AndroidAutoDevelopQueue singleton. Uses existing adapter/backend implementations, not a fork.
 * This does NOT test product GPU/capture admission wiring. Only synthetic inputs may be supplied.
 * A fresh instrumentation process is required, especially when opting into the old reuse registry.
 * No root inventory scan, recovery cleanup, app-data clearing, or filesystem/media deletion occurs.
 */
@RequiresApi(29)
internal class LegacyCpuFixture private constructor(
    val app: Context,
    val namespaceRoot: File,
    manualAdapters: List<RendererAdapter>,
    private val useReuseRegistry: Boolean,
) : AutoCloseable {
    private val coordinator = AutoDevelopCoordinator()
    private val io = Executors.newSingleThreadExecutor { Thread(it, "legacy-cpu-fixture-io") }
    private val cancellation = Executors.newSingleThreadExecutor { Thread(it, "legacy-cpu-fixture-cancel") }
    private val catalog = AutoRendererCatalog(manualAdapters + BoundedJpegRendererAdapter.privateTrial(
        if (useReuseRegistry) PrivateTrialReuseRegistry::forRequest else { _ -> null }))
    private val privateRoot = File(app.noBackupFilesDir, "raw-captures")
    private val queue = AutoDevelopQueue(io, coordinator,
        journalFactory = { request ->
            check(request.interactive) { "Legacy fixture accepts explicit interactive requests only" }
            val directory = RenderedJpegFiles(privateRoot, request.captureId, request.generation).generationRoot()
            AutoDevelopJournal(request.captureId, request.generation,
                AndroidAtomicJournalStorage(requireNotNull(directory.parentFile), request.generation.toString(), CaptureJournalFile.AUTO_DEVELOP))
        },
        backendFactory = { request -> AndroidAutoDevelopBackend(app, request, catalog) },
        cancellationIo = cancellation)
    private val startup = queue.restore(emptyList<AutoDevelopRequest>())
    @Volatile private var closing = false
    @Volatile private var closed = false
    private val createdRunners = mutableListOf<AutoDevelopRunner>()
    val execution = Execution()

    private fun requireOpen() { check(!closing && !closed) { "Legacy fixture is closing/closed" } }
    fun get(): AutoDevelopQueue { requireOpen(); return queue }
    /** Useful for mechanical test migration; rejects accidental use of the installed app context. */
    fun get(context: Context): AutoDevelopQueue {
        check(context.applicationContext === app) { "Use legacy.app, never the installed app context" }
        return get()
    }
    fun startupCompletion(): CompletableFuture<Unit> = startup
    /** Fault-only runner on this harness's coordinator. Caller must stop and finish all synchronous
     * runner calls before close(); the queue cannot observe these independent resource owners.
     * Publication stays disabled, and no product singleton or stop hook is attached.
     */
    @Synchronized fun runner(journal: AutoDevelopJournal, backend: AutoDevelopBackend,
        observations: () -> AutoDevelopConditions?): AutoDevelopRunner {
        requireOpen()
        return AutoDevelopRunner(journal, coordinator, backend,
            cancellationIo = cancellation,
            interactive = true,
            observations = observations,
            allowPublication = { false },
            isCurrent = { !closing && !closed }).also { createdRunners += it }
    }

    fun preparePrivateTrial(source: SharedOriginalReference,
        recipe: JpegGenerationRecipe = JpegGenerationRecipe.trial(),
        reuseSession: PrivateTrialReuseSession? = null,
        shouldStop: () -> Boolean = { false }): CompletableFuture<PrivateTrialHandle> {
        requireOpen()
        require(recipe.isTrial && JpegGenerationRecipe.fromTrialSnapshot(recipe.snapshot) != null)
        require(reuseSession == null || useReuseRegistry) { "Reuse needs explicit fixture opt-in" }
        val id = ManualDevelopRequests.identity(source)
        return queue.preparePrivateTrial(id, shouldStop) {
            ManualDevelopRequests(source, selectionStorage(id)).prepare(recipe.snapshot).also {
                if (useReuseRegistry) PrivateTrialReuseRegistry.bind(reuseSession, it)
            }
        }
    }

    fun prepareManual(source: SharedOriginalReference, recipe: JpegGenerationRecipe): CompletableFuture<ManualDevelopHandle> {
        requireOpen()
        require(!recipe.isTrial)
        val id = ManualDevelopRequests.identity(source)
        return queue.prepareManual(id) { ManualDevelopRequests(source, selectionStorage(id)).prepare(recipe.snapshot) }
    }
    private fun selectionStorage(id: String) =
        AndroidAtomicJournalStorage(privateRoot, id, CaptureJournalFile.MANUAL_REQUEST)

    /** All facade operations use exactly the coordinator owned by this queue. */
    inner class Execution internal constructor() {
        private val attempts = mutableSetOf<AutoDevelopCoordinator.Attempt>()
        private val captures = mutableSetOf<AutoDevelopCoordinator.CaptureReservation>()
        @Synchronized fun updateConditions(value: AutoDevelopConditions) {
            requireOpen()
            if (useReuseRegistry && AutoDevelopPolicy.blockers(value).isNotEmpty()) PrivateTrialReuseRegistry.invalidateAll()
            queue.updateConditions(value)
        }
        @Synchronized fun tryStartObserved(value: AutoDevelopConditions): AutoDevelopCoordinator.Attempt? {
            requireOpen()
            return coordinator.tryStartObserved(value)?.also { attempts += it }
        }
        fun shouldStop(attempt: AutoDevelopCoordinator.Attempt) = coordinator.shouldStop(attempt)
        @Synchronized fun completeAfterResourcesClosed(attempt: AutoDevelopCoordinator.Attempt): Boolean {
            check(!closed)
            check(attempt in attempts) { "Fixture facade does not own this external attempt" }
            return coordinator.completeAfterResourcesClosed(attempt).also {
                if (it) { attempts -= attempt; queue.wake() }
            }
        }
        @Synchronized fun reserveCapture(): AutoDevelopCoordinator.CaptureReservation {
            requireOpen()
            if (useReuseRegistry) PrivateTrialReuseRegistry.invalidateAll()
            return queue.reserveCapture().also { captures += it }
        }
        @Synchronized fun releaseCapture(reservation: AutoDevelopCoordinator.CaptureReservation) {
            check(!closed)
            check(reservation in captures) { "Foreign capture reservation" }
            queue.releaseCapture(reservation)
            captures -= reservation
        }
        fun requestStop() { check(!closed); queue.requestStop() }
        fun requestStop(attempt: AutoDevelopCoordinator.Attempt) {
            check(!closed)
            coordinator.requestStop(attempt)
            queue.stopObserved(attempt)
        }
        @Synchronized internal fun requireExternalOwnersReleased() {
            check(attempts.isEmpty() && captures.isEmpty()) { "External fixture attempt/capture owner is still held" }
        }
    }

    /** No forced shutdown, fake completion, or cleanup after an unconfirmed close. A failed close
     * retains this harness and its files; release the caller-owned handles then retry close().
     * Caller must first close its PrivateTrialDelivery/claimed images and stop typed handles.
     */
    @Synchronized override fun close() {
        if (closed) return
        closing = true
        queue.updateConditions(AutoDevelopConditions())
        queue.requestStop()
        createdRunners.forEach { it.requestStop() }
        if (useReuseRegistry) PrivateTrialReuseRegistry.invalidateAll()
        startup.get(60, TimeUnit.SECONDS)
        // Drain cancellation callbacks, then require the queue's retained resource owner to close.
        cancellation.submit { Unit }.get(60, TimeUnit.SECONDS)
        queue.manualOriginalIo { Unit }.get(60, TimeUnit.SECONDS)
        execution.requireExternalOwnersReleased()
        check(createdRunners.none { it.hasUnreleasedResources }) {
            "Independent legacy runner still owns resources; preserve artifacts and retry after release"
        }
        // No callers may submit after closing begins. Keep artifacts, including successful output.
        cancellation.shutdown()
        io.shutdown()
        check(cancellation.awaitTermination(60, TimeUnit.SECONDS)) { "Legacy cancellation worker remains live" }
        check(io.awaitTermination(60, TimeUnit.SECONDS)) { "Legacy IO worker remains live" }
        closed = true
    }

    companion object {
        fun create(target: Context = InstrumentationRegistry.getInstrumentation().targetContext,
            manualAdapters: List<RendererAdapter> = emptyList(), useReuseRegistry: Boolean = false): LegacyCpuFixture {
            val args = InstrumentationRegistry.getArguments()
            check(BuildConfig.DEBUG && BuildConfig.BUILD_TYPE == "debug")
            check(target.packageName == "dev.cinematic.p0")
            check(Build.VERSION.SDK_INT >= 29 && Build.HARDWARE in setOf("ranchu", "goldfish"))
            check(args.getString("isolated") == "true" && args.getString("legacyCpuFixture") == "true") {
                "Explicit isolated legacyCpuFixture instrumentation opt-in required"
            }
            manualAdapters.forEach {
                require(it.quality == AutoRenderQuality.MANUAL_BOUNDED_1024_PRELIMINARY &&
                    it.contract == JpegGenerationRecipe.PUBLICATION_CONTRACT)
            }
            val installed = target.applicationContext
            val namespace = "legacy-cpu-fixture-${UUID.randomUUID()}"
            val parent = installed.cacheDir.canonicalFile
            val root = File(parent, namespace)
            check(root.mkdir() && root.canonicalFile.parentFile == parent)
            fun child(name: String) = File(root, name).also { check(it.mkdir()) }
            val cache = child("cache")
            val files = child("files")
            val state = child("no-backup")
            val app = object : ContextWrapper(installed) {
                override fun getApplicationContext(): Context = this
                override fun getCacheDir(): File = cache
                override fun getFilesDir(): File = files
                override fun getNoBackupFilesDir(): File = state
                override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                    installed.getSharedPreferences("$namespace-$name", mode)
            }
            return LegacyCpuFixture(app, root, manualAdapters, useReuseRegistry)
        }
    }
}
