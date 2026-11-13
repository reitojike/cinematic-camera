package dev.cinematic.p0.photoroll

import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.raw.PhotoAdjustments
import dev.cinematic.p0.rawcapture.*
import dev.cinematic.p0.testing.syntheticTrialDng
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Empty emulator only: generated DNG, real native render, retained handle, journal and publisher.
 * Initial UI observations are controlled; the Service and render use real resource observations.
 * Two cases exercise Compose Save/cancel; photographic quality/performance is not assessed. */
@RunWith(AndroidJUnit4::class)
class ForegroundExportOwnerTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val ready = PhotoRollResources(true, 0, false, 1024L * 1024 * 1024,
        PhotoRollCameraSnapshot(false, false, false, false), true, true)
    private val allowed get() = ready.developConditions(true)

    @Before fun isolatedOnly() {
        check(!unsettledFixture) { "Previous synthetic fixture has unconfirmed resources; stop this suite" }
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        check(Build.HARDWARE in setOf("goldfish", "ranchu") && Build.VERSION.SDK_INT >= 29)
    }

    /** Picker-result integration only: no DocumentsUI, external provider, or persistable grant proof. */
    @Test fun importedSyntheticDngEditsAndPublishesThroughRealForegroundService() = importedSyntheticDng(false)

    /** Real system picker, restricted to one uniquely named self-created Downloads source. */
    @Test fun documentsUiSyntheticDngEditsAndPublishesThroughRealForegroundService() = importedSyntheticDng(true)

    /** Deliberate idle intervals test host/ADB lifetime, not rendering performance or longer operation timeouts. */
    @Test fun optInMemoryDiagnosticTwentyMinuteAdbRehearsal() {
        org.junit.Assume.assumeTrue(dev.cinematic.p0.BuildConfig.BUILD_TYPE == "memoryDiagnostic")
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("memoryRehearsal20") == "true")
        check(dev.cinematic.p0.memory.MemoryAdmissionDiagnostic.enabled)
        val token = requireNotNull(InstrumentationRegistry.getArguments().getString("memoryRehearsalToken"))
        require(token.matches(Regex("[0-9a-f]{32}")))
        val control = File(context.cacheDir.canonicalFile, "memory-rehearsal-$token")
        check(control.mkdir()) { "Rehearsal control already exists" }
        val stop = File(control, "stop")
        val started = android.os.SystemClock.elapsedRealtime()
        fun holdUntil(minutes: Long) {
            val target = started + TimeUnit.MINUTES.toMillis(minutes)
            check(!stop.exists()) { "Host collector stopped; do not continue synthetic cycles" }
            while (android.os.SystemClock.elapsedRealtime() < target) {
                check(!stop.exists()) { "Host collector stopped; do not continue synthetic cycles" }
                Thread.sleep(minOf(1000L, target - android.os.SystemClock.elapsedRealtime()).coerceAtLeast(1L))
            }
        }
        var primary: Throwable? = null
        try {
            for ((cycle, offset) in listOf(0L, 8L, 16L).withIndex()) {
                holdUntil(offset)
                check(!unsettledFixture)
                instrumentation.sendStatus(2, android.os.Bundle().apply {
                    putString("stream", "MEMORY_REHEARSAL cycle=${cycle + 1} phase=BEGIN\n")
                })
                // Each successful cycle proves publication/readback and releases only its own synthetic resources.
                // Any failure escapes immediately: no automatic retry or next cycle after an unknown owner/publication.
                memoryDiagnosticDocumentsUiEditsPublishesAndReleasesWithRealGpuLogs()
                instrumentation.sendStatus(2, android.os.Bundle().apply {
                    putString("stream", "MEMORY_REHEARSAL cycle=${cycle + 1} phase=RELEASED\n")
                })
            }
            // Collector starts before instrumentation and ends after 20 minutes. Keep this owned process alive
            // for one extra minute so normal host shutdown is observable before the runner ends the process.
            holdUntil(21)
            check(!unsettledFixture && ForegroundExports.gate.active() == null)
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try {
                if (stop.exists()) check(stop.delete())
                check(control.delete()) { "Rehearsal control cleanup failed" }
            } catch (closing: Throwable) {
                val first = primary
                if (first != null) first.addSuppressed(closing) else throw closing
            }
        }
    }

    @Test fun memoryDiagnosticDocumentsUiEditsPublishesAndReleasesWithRealGpuLogs() {
        org.junit.Assume.assumeTrue(dev.cinematic.p0.BuildConfig.BUILD_TYPE == "memoryDiagnostic")
        check(dev.cinematic.p0.memory.MemoryAdmissionDiagnostic.enabled)
        val started = android.os.SystemClock.elapsedRealtime()
        importedSyntheticDng(true, memoryMeasurement = true)
        val command = "logcat -d --pid=${android.os.Process.myPid()} -s CinematicMemory:I *:S"
        val lines = android.os.ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readLines() }
        val events = lines.filter { line -> Regex("elapsedMs=(\\d+)").find(line)
            ?.groupValues?.get(1)?.toLong()?.let { it >= started } == true }
        for (stage in listOf("ORIGINAL_DECODE", "BOUNDED_PREVIEW", "ORIGINAL_SAVE"))
            assertTrue("Missing real memory stage $stage", events.any { it.contains("stage=$stage ") })
        for (budget in listOf(dev.cinematic.p0.gpu.GpuMemoryLedger.PREVIEW_BUDGET,
            dev.cinematic.p0.gpu.GpuMemoryLedger.EXPORT_BUDGET)) {
            val ledger = events.filter { it.contains("GPU_TEXTURE_LEDGER ") && it.contains("budgetBytes=$budget ") }
            assertTrue("Missing real GPU allocation for budget $budget", ledger.any {
                Regex(" reservedBytes=(\\d+)").find(it)?.groupValues?.get(1)?.toLong()?.let { bytes -> bytes > 0 } == true })
            assertTrue("Final GPU ledger remains allocated", ledger.last().contains("reservedBytes=0 "))
        }
    }

    private fun importedSyntheticDng(documentsUi: Boolean, memoryMeasurement: Boolean = false) {
        org.junit.Assume.assumeTrue(dev.cinematic.p0.BuildConfig.DEBUG &&
            (context.packageName == "dev.cinematic.p0" ||
                (memoryMeasurement && context.packageName == "dev.cinematic.p0.memorydiagnostic")))
        org.junit.Assume.assumeTrue(InstrumentationRegistry.getArguments().getString(if (documentsUi) "importUiFgsDocuments" else "importUiFgs") == "true")
        // The measurement rehearsal reads only rows it creates and the exact SAF grant from DocumentsUI.
        // Do not grant media-library permissions to the new diagnostic identity.
        if (!memoryMeasurement) cameraRollReadPermissions(Build.VERSION.SDK_INT).forEach {
            check(context.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                "Reuse an already-authorized synthetic fixture app; do not change permissions"
            }
        }
        check(ForegroundExports.gate.active() == null)
        if (documentsUi) check(androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
            .currentPackageName?.endsWith(".documentsui") != true) { "Existing system picker is in use" }
        val app = context.applicationContext
        val resolver = app.contentResolver
        val originalGrants = resolver.persistedUriPermissions.map { Triple(it.uri, it.isReadPermission, it.isWritePermission) }.toSet()
        val imports = AndroidDngImports.get(app)
        val files = DngImportJournalFiles(File(app.noBackupFilesDir.canonicalFile, "dng-imports"))
        val beforeImports = files.records()
        check(beforeImports.all { it.phase in setOf(DngImportPhase.READY, DngImportPhase.CANCELLED) })
        val beforeIds = beforeImports.map { it.target.operationId }.toSet()
        val beforeJpegs = jpegCount()
        val name = "synthetic_import_ui_${UUID.randomUUID()}.dng"
        var sourceUri: Uri? = null
        var fingerprint: OriginalFingerprint? = null
        var imported: DngImportRecord? = null
        var saved: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle? = null
        var publication: JpegPublicationRecord? = null
        var scenario: ActivityScenario<PhotoRollActivity>? = null
        var monitor: android.app.Instrumentation.ActivityMonitor? = null
        var pickerLaunchAttempted = false
        var ownedPickerPackage: String? = null
        var observation: PhotoRollCameraOwners.Owner? = null
        val opened = java.util.concurrent.atomic.AtomicInteger()
        val released = java.util.concurrent.atomic.AtomicInteger()
        var inputObserver: AutoCloseable? = null
        var primary: Throwable? = null
        var verified = false
        var editorReady = false
        try {
            check(dev.cinematic.p0.gpu.DevelopEngineSetting(app).current() == dev.cinematic.p0.gpu.DevelopEngine.GPU)
            inputObserver = GpuPhotoRollInputs.observeForDiagnostics(object : GpuPhotoRollInputs.DiagnosticObserver {
                override fun preflightOpened() { opened.incrementAndGet() }
                override fun released() { released.incrementAndGet() }
            })
            val collection = if (documentsUi) MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = requireNotNull(resolver.insert(collection,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, if (documentsUi) "Download/" else "Pictures/Cinematic Camera Synthetic Tests/")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                })).also { sourceUri = it }
            // Unique bytes prevent reusing an earlier completed import with the same fingerprint.
            val random = java.util.Random(UUID.randomUUID().leastSignificantBits)
            val bytes = requireNotNull(resolver.openOutputStream(uri, "w")).use {
                dev.cinematic.p0.testing.writeSyntheticTrialDng(it, 64, 48, 1) { _, _ -> 164 + random.nextInt(3500) }
            }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) == 1)
            fingerprint = DngContentVerifier.identify(bytes, { requireNotNull(resolver.openInputStream(uri)) })
            check(beforeImports.none { it.fingerprint == fingerprint })
            instrumentation.runOnMainSync {
                observation = AndroidPhotoRollCameraObservations.register { PhotoRollCameraSnapshot(false, false, false, false) }
            }
            if (!documentsUi) monitor = instrumentation.addMonitor(android.content.IntentFilter(android.content.Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(android.content.Intent.CATEGORY_OPENABLE); addDataType("*/*")
            }, android.app.Instrumentation.ActivityResult(android.app.Activity.RESULT_OK,
                android.content.Intent().setData(uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)), true)
            scenario = ActivityScenario.launch(PhotoRollActivity::class.java)
            click("外部DNGを取り込む")
            if (documentsUi) {
                check(androidx.test.uiautomator.UiDevice.getInstance(instrumentation).currentPackageName == context.packageName)
                pickerLaunchAttempted = true
            }
            click("DNGを選ぶ・選び直す")
            if (documentsUi) selectSyntheticDownloadThroughDocumentsUi(name) { ownedPickerPackage = it }
            try {
                await {
                    val pickerReturned = documentsUi || requireNotNull(monitor).hits == 1
                    val state = imports.state.value
                    val items = state.items.filter { it.id !in beforeIds }
                    pickerReturned && !state.busy && items.any { it.ready }
                }
            } catch (error: Throwable) {
                // A single pre-cleanup snapshot, no source name/URI/hash or arbitrary screen dump.
                try {
                    val state = imports.state.value
                    val items = state.items.filter { it.id !in beforeIds }
                    error.addSuppressed(AssertionError("Synthetic import checkpoint: hits=${monitor?.hits} " +
                        "lifecycle=${scenario.state} busy=${state.busy} message=${state.message} " +
                        "readyUriPresent=${state.readyUri != null} newItems=${items.size} " +
                        "readyItems=${items.count { it.ready }} inputOpened=${opened.get()} inputReleased=${released.get()} " +
                        "fgsActive=${ForegroundExports.gate.active() != null}"))
                } catch (observationFailure: Throwable) {
                    if (observationFailure !== error) error.addSuppressed(observationFailure)
                }
                throw error
            }
            imported = files.records().filter { it.target.operationId !in beforeIds }.single()
            val copy = requireNotNull(imported)
            if (documentsUi) {
                val selected = Uri.parse(copy.sourceUri)
                check(android.provider.DocumentsContract.isDocumentUri(app, selected))
                check(selected.authority == "com.android.providers.downloads.documents")
                // Never print a provider URI or document name on mismatch.
                requireNotNull(resolver.query(selected, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)).use {
                    check(it.count == 1 && it.moveToFirst() && it.getString(0) == name)
                }
                check(resolver.persistedUriPermissions.map { Triple(it.uri, it.isReadPermission, it.isWritePermission) }.toSet() == originalGrants) {
                    "Import changed retained read-grant ownership"
                }
            } else assertEquals(uri.toString(), copy.sourceUri)
            assertEquals(fingerprint, copy.fingerprint)
            assertNotEquals(uri.toString(), copy.uri)
            assertEquals(DngImportGrant.SESSION_ONLY, copy.grant)
            verifySyntheticRow(Uri.parse(copy.uri), copy.target.name)
            DngContentVerifier.verify(requireNotNull(fingerprint), { requireNotNull(resolver.openInputStream(Uri.parse(copy.uri))) })
            action("JPEGを保存") // Real Panel completion delivered the imported URI to the editor.
            editorReady = true // A prepared save proves that the existing queue has been used.
            click("比較"); click("表示")
            click("STRENGTH")
            fun slider(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (node == null) return null
                if (node.contentDescription?.toString() == "STRENGTH" && node.rangeInfo != null && node.isEnabled) return node
                for (i in 0 until node.childCount) slider(node.getChild(i))?.let { return it }
                return null
            }
            var ruler: AccessibilityNodeInfo? = null
            await { ruler = slider(instrumentation.uiAutomation.rootInActiveWindow); ruler != null }
            assertTrue(requireNotNull(ruler).performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                android.os.Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, .6f) }))
            click("目盛りを閉じる")
            action("JPEGを保存")
            val release = CountDownLatch(1)
            val entered = CountDownLatch(1)
            val barrier = AndroidAutoDevelopQueue.get(app).manualOriginalIo {
                entered.countDown(); check(release.await(120, TimeUnit.SECONDS))
            }
            var foregroundFailure: Throwable? = null
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                click("JPEGを保存")
                await { ForegroundExports.gate.active()?.started == true }
                val ticket = requireNotNull(ForegroundExports.gate.active())
                saved = ticket.value.identity as AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle
                assertEquals(copy.uri, requireNotNull(saved).request.source.uri)
                assertEquals(.6, requireNotNull(saved).appliedStrength, .000001)
                scenario.moveToState(Lifecycle.State.CREATED)
                assertFalse(ticket.stopRequested)
            } catch (error: Throwable) { foregroundFailure = error; throw error }
            finally {
                release.countDown()
                try { barrier.get(30, TimeUnit.SECONDS) } catch (closing: Throwable) {
                    val first = foregroundFailure
                    if (first == null) throw closing
                    if (first !== closing) first.addSuppressed(closing)
                }
            }
            await { ForegroundExports.gate.active() == null }
            val handle = requireNotNull(saved)
            assertFalse(handle.hasUnreleasedResources)
            assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, handle.state.value.phase)
            val generation = RenderedJpegFiles(File(app.noBackupFilesDir, "raw-captures"),
                handle.request.captureId, handle.request.generation).generationRoot()
            val storage = AndroidAtomicJournalStorage(requireNotNull(generation.parentFile), handle.request.generation.toString(),
                CaptureJournalFile.JPEG_PUBLICATION)
            publication = requireNotNull(JpegPublicationJournal(handle.request.captureId, handle.request.generation, storage).read())
            val record = requireNotNull(publication)
            val render = record.render as ManualOriginalTrialRender
            assertEquals(handle.request.source, render.request.source)
            assertEquals(handle.request.recipe, render.request.recipe)
            assertEquals(record.uri, handle.state.value.uri)
            assertEquals(JpegPublicationPhase.PUBLISHED, record.phase)
            assertNotEquals(copy.uri, record.uri)
            verifySyntheticRow(Uri.parse(record.uri), record.target.displayName)
            val inspected = AndroidJpegInspector().inspect({ requireNotNull(resolver.openInputStream(Uri.parse(record.uri))) },
                record.fingerprint.bytes) { false }
            assertEquals(record.fingerprint, inspected.fingerprint)
            assertEquals(64, inspected.output.width); assertEquals(48, inspected.output.height)
            assertEquals("sRGB", inspected.output.color)
            assertFalse(File(generation, "original-work").exists())
            assertEquals(beforeJpegs + 1, jpegCount())
            scenario.moveToState(Lifecycle.State.RESUMED)
            action("保存したJPEGを照合して開く")
            assertEquals(beforeJpegs + 1, jpegCount())
            DngContentVerifier.verify(requireNotNull(fingerprint), { requireNotNull(resolver.openInputStream(uri)) })
            DngContentVerifier.verify(requireNotNull(fingerprint), { requireNotNull(resolver.openInputStream(Uri.parse(copy.uri))) })
            verified = true
        } catch (error: Throwable) { primary = error; throw error }
        finally {
            var cleanup: Throwable? = null
            fun attempt(block: () -> Unit) { try { block() } catch (error: Throwable) {
                val first = primary ?: cleanup
                if (first == null) cleanup = error else if (first !== error) first.addSuppressed(error)
            } }
            if (documentsUi) attempt {
                val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
                val owned = ownedPickerPackage
                if (owned != null) {
                    var backs = 0
                    while (device.currentPackageName == owned && backs++ < 3) {
                        device.pressBack(); device.waitForIdle(1000)
                    }
                    check(device.currentPackageName != owned) { "System picker closure unconfirmed" }
                } else if (pickerLaunchAttempted) {
                    check(device.currentPackageName?.endsWith(".documentsui") != true) { "System picker ownership/closure unconfirmed" }
                }
            }
            attempt { scenario?.close() }
            attempt { imports.pause(); await { !imports.state.value.busy } }
            attempt { ForegroundExports.gate.active()?.value?.let { ForegroundExports.cancel(it) } }
            attempt { await { ForegroundExports.gate.active() == null } }
            attempt { GpuPhotoRollRetiredOwners.releaseBeforeOpen() }
            if (editorReady) attempt { AndroidManualOriginalTrialSaves.discardPreview(app).get(60, TimeUnit.SECONDS) }
            attempt { check(saved?.hasUnreleasedResources != true) }
            // A preflight-before-open failure may release a token without an opened callback.
            // On failure a mismatch is unconfirmed closure, not proof of a leak.
            attempt { check((!verified || opened.get() > 0) && opened.get() == released.get()) }
            attempt {
                @Suppress("DEPRECATION")
                val manager = app.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                await { manager.getRunningServices(Int.MAX_VALUE).none { it.service.className == ForegroundExportService::class.java.name } }
            }
            attempt { inputObserver?.close() }
            attempt { monitor?.let(instrumentation::removeMonitor) }
            attempt { instrumentation.runOnMainSync { observation?.close() } }
            if (verified && primary == null && cleanup == null) attempt {
                val rows = listOf(requireNotNull(sourceUri) to name,
                    Uri.parse(requireNotNull(imported).uri) to requireNotNull(imported).target.name,
                    Uri.parse(requireNotNull(publication).uri) to requireNotNull(publication).target.displayName)
                check(rows.map { it.first }.toSet().size == 3)
                rows.forEach { (uri, label) -> verifySyntheticRow(uri, label) }
                rows.forEach { (uri, label) ->
                    verifySyntheticRow(uri, label)
                    check(resolver.delete(uri, "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.IS_PENDING}=0",
                        arrayOf(app.packageName, label)) == 1)
                }
                // Preserve unique private journal evidence; never enumerate/delete another task's journals.
            }
            if (primary == null) cleanup?.let { throw it }
        }
    }

    private fun selectSyntheticDownloadThroughDocumentsUi(name: String, opened: (String) -> Unit) {
        val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
        await { device.currentPackageName?.endsWith(".documentsui") == true }
        val pickerPackage = requireNotNull(device.currentPackageName)
        opened(pickerPackage)
        val roots = androidx.test.uiautomator.By.res(pickerPackage, "roots_list")
        fun unique(selector: androidx.test.uiautomator.BySelector): androidx.test.uiautomator.UiObject2 {
            var found: androidx.test.uiautomator.UiObject2? = null
            await {
                check(device.currentPackageName == pickerPackage) { "System picker lost foreground" }
                val matches = device.findObjects(selector)
                check(matches.size <= 1) { "Ambiguous system picker target" }
                found = matches.singleOrNull(); found != null
            }
            return requireNotNull(found)
        }
        fun clickUnique(selector: androidx.test.uiautomator.BySelector) {
            await {
                check(device.currentPackageName == pickerPackage) { "System picker lost foreground" }
                val matches = device.findObjects(selector)
                check(matches.size <= 1) { "Ambiguous system picker target" }
                val target = matches.singleOrNull()
                if (target == null) false else try {
                    target.click(); true
                } catch (_: androidx.test.uiautomator.StaleObjectException) {
                    // In pinned UiAutomator 2.4.1 click refreshes the node before sending input.
                    // Re-query this exact target within the same wait; never repeat a sent click.
                    false
                }
            }
        }
        if (!device.hasObject(roots)) clickUnique(androidx.test.uiautomator.By.pkg(pickerPackage)
            .clazz("android.widget.ImageButton").desc(java.util.regex.Pattern.compile(
                "Show roots|Open navigation drawer|ルートを表示|ルートを表示する|ナビゲーション ドロワーを開く")))
        clickUnique(androidx.test.uiautomator.By.pkg(pickerPackage).text(java.util.regex.Pattern.compile("Downloads|ダウンロード"))
            .hasAncestor(roots))
        await { !device.hasObject(roots) }
        val directory = androidx.test.uiautomator.By.res(pickerPackage, "dir_list")
        val exact = androidx.test.uiautomator.By.pkg(pickerPackage).text(name).hasAncestor(directory)
        // No fallback to Recents, Images, other roots, screenshots, or arbitrary document clicks.
        val list = unique(directory)
        var rewinds = 0
        while (list.isScrollable && list.scroll(androidx.test.uiautomator.Direction.UP, .8f)) {
            check(++rewinds <= 20) { "Synthetic directory navigation bound exceeded" }
        }
        var selected: androidx.test.uiautomator.UiObject2? = null
        var pages = 0
        var endReached = false
        await {
            check(device.currentPackageName == pickerPackage) { "System picker lost foreground" }
            val matches = device.findObjects(exact)
            check(matches.size <= 1) { "Ambiguous synthetic document" }
            selected = matches.singleOrNull()
            if (selected == null) {
                check(!endReached) { "Synthetic document absent" }
                if (list.isScrollable) {
                    check(++pages <= 20) { "Synthetic document not found within navigation bound" }
                    // false means the end was reached, not that the last scroll did not move.
                    // The next iteration still searches that final viewport before failing.
                    endReached = !list.scroll(androidx.test.uiautomator.Direction.DOWN, .8f)
                }
            }
            selected != null
        }
        requireNotNull(selected).click()
        await { device.currentPackageName == context.packageName }
    }

    private fun verifySyntheticRow(uri: Uri, name: String) {
        check(uri.scheme == "content" && uri.authority == "media")
        val columns = arrayOf(MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.IS_PENDING)
        requireNotNull(context.contentResolver.query(uri, columns, null, null, null)).use {
            check(it.count == 1 && it.moveToFirst() && it.getString(0) == name && it.getString(1) == context.packageName &&
                !it.isNull(2) && it.getInt(2) == 0)
        }
    }

    @Test fun screenSavePublishesInBackgroundAndReturnShowsConfirmedOwner() = screenSave(false)
    @Test fun screenCancelAfterReturnWorksBeforePreviewReloadCompletes() = screenSave(true)

    private fun screenSave(cancelAfterReturn: Boolean) {
        cameraRollReadPermissions(Build.VERSION.SDK_INT).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it)
        }
        foreground { scenario -> Fixture().use { f ->
            f.initialize(.5, PhotoAdjustments.NONE)
            f.screen = scenario
            scenario.recreate() // The real list now observes this fixture's newly inserted source.
            click(f.name)
            action("JPEGを保存")
            val release = CountDownLatch(1)
            val entered = CountDownLatch(1)
            val barrier = AndroidAutoDevelopQueue.get(context).manualOriginalIo {
                entered.countDown(); check(release.await(120, TimeUnit.SECONDS))
            }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                click("JPEGを保存") // Real PhotoRollScreen callback, not an injected save action.
                await { ForegroundExports.gate.active()?.started == true }
                val ticket = ForegroundExports.gate.active()
                assertSame(f.handle, requireNotNull(ticket).value.identity)
                scenario.moveToState(Lifecycle.State.CREATED)
                assertFalse(ticket.stopRequested)
                if (cancelAfterReturn) {
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    // Preview is queued behind the barrier; the process banner must still allow cancel.
                    click("保存を取り消す")
                    await { ticket.stopRequested }
                    assertSame(ticket, ForegroundExports.gate.active())
                }
            } finally { release.countDown(); barrier.get(30, TimeUnit.SECONDS) }
            await { ForegroundExports.gate.active() == null }
            if (cancelAfterReturn) {
                assertEquals(ManualOriginalTrialSavePhase.READY, f.handle.state.value.phase)
                assertNull(f.journal().read())
                assertNull(f.storage(CaptureJournalFile.MANUAL_REQUEST).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
                action("JPEGを保存") // Ready UI is not a publication receipt or an automatic retry.
                assertEquals(f.beforeJpegs, jpegCount())
            } else {
                assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, f.handle.state.value.phase)
                scenario.moveToState(Lifecycle.State.RESUMED)
                action("保存したJPEGを照合して開く")
                assertEquals(f.beforeJpegs + 1, jpegCount())
            }
            f.verifyOriginal()
            f.screenVerified = true
        } }
    }

    private fun action(label: String): AccessibilityNodeInfo {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null) return null
            if (!node.refresh()) return null // Resume can leave UiAutomation's old Save node cached.
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        var result: AccessibilityNodeInfo? = null
        var attempts = 0
        fun scroll(node: AccessibilityNodeInfo?): Boolean {
            if (node == null) return false
            if (node.className == "android.widget.ScrollView" && node.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) return true
            for (i in 0 until node.childCount) if (scroll(node.getChild(i))) return true
            return false
        }
        try { await {
            instrumentation.waitForIdleSync()
            var node = find(instrumentation.uiAutomation.rootInActiveWindow)
            while (node != null && !node.isClickable) node = node.parent
            result = node?.takeIf { it.refresh() && it.isClickable && it.isEnabled && it.isVisibleToUser }
            // Compose can omit off-screen semantics entirely until its container scrolls.
            if ((node == null || !node.isVisibleToUser) && ++attempts % 10 == 0) scroll(instrumentation.uiAutomation.rootInActiveWindow)
            result != null
        } } catch (failure: IllegalStateException) {
            val labels = mutableListOf<String>()
            fun describe(node: AccessibilityNodeInfo?) {
                if (node == null || labels.size >= 80) return
                (node.contentDescription ?: node.text)?.toString()?.let { labels += "$it [enabled=${node.isEnabled}, visible=${node.isVisibleToUser}]" }
                for (i in 0 until node.childCount) describe(node.getChild(i))
            }
            describe(instrumentation.uiAutomation.rootInActiveWindow)
            throw AssertionError("Missing enabled visible action: $label; synthetic screen: ${labels.joinToString(" | ")}", failure)
        }
        return requireNotNull(result)
    }
    private fun click(label: String) {
        val node = action(label)
        val target = if (label == "目盛りを閉じる") "RULER_CLOSE" else "OTHER_FIXED_ACTION"
        fun state(): String {
            val refreshed = node.refresh()
            val root = instrumentation.uiAutomation.rootInActiveWindow
            return "fresh=$refreshed enabled=${node.isEnabled} visible=${node.isVisibleToUser} " +
                "clickable=${node.isClickable} sameWindow=${root?.windowId == node.windowId} " +
                "clickAction=${node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK }}"
        }
        val before = state()
        check(node.refresh() && node.isEnabled && node.isVisibleToUser && node.isClickable) {
            "UI_CLICK target=$target phase=BEFORE_NOT_SENT $before"
        }
        val accepted = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        // Never replay a false/ambiguous action; retain evidence of a possible product failure.
        if (!accepted) {
            val failure = AssertionError("UI_CLICK target=$target phase=ACTION_RETURNED_FALSE before=[$before]")
            try { failure.addSuppressed(IllegalStateException("UI_CLICK after=[${state()}]")) }
            catch (diagnostic: Throwable) {
                failure.addSuppressed(IllegalStateException("UI_CLICK after=UNAVAILABLE exception=${diagnostic.javaClass.simpleName}"))
            }
            throw failure
        }
    }

    @Test fun acceptedSavePublishesSameRecipeAfterActivityStopsAndRetryDoesNotDuplicate() = foreground { scenario ->
        Fixture().use { f ->
            f.initialize()
            val release = CountDownLatch(1)
            val entered = CountDownLatch(1)
            val barrier = AndroidAutoDevelopQueue.get(context).manualOriginalIo {
                entered.countDown(); check(release.await(120, TimeUnit.SECONDS))
            }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                instrumentation.runOnMainSync { assertTrue(f.ui.startForegroundSave(context)) }
                await { ForegroundExports.gate.active()?.started == true }
                assertEquals(PhotoRollSavePhase.SAVING, f.ui.snapshot.phase)
                // Duplicate UI activation must neither allocate nor replace the current save.
                val ticket = ForegroundExports.gate.active()
                instrumentation.runOnMainSync { assertTrue(f.ui.startForegroundSave(context)) }
                assertSame(ticket, ForegroundExports.gate.active())
                scenario.moveToState(Lifecycle.State.CREATED)
            } finally { release.countDown(); barrier.get(30, TimeUnit.SECONDS) }
            await { ForegroundExports.gate.active() == null }
            assertEquals(ManualOriginalTrialSavePhase.PUBLISHED, f.handle.state.value.phase)
            val record = requireNotNull(f.journal().read())
            val render = record.render as ManualOriginalTrialRender
            assertEquals(f.handle.request.source, render.request.source)
            assertEquals(f.handle.request.recipe, render.request.recipe)
            assertEquals(f.handle.appliedStrength, render.appliedStrength, 0.0)
            assertEquals(f.handle.appliedAdjustments, render.adjustments)
            assertEquals(f.beforeJpegs + 1, jpegCount())
            DngContentVerifier.verify(record.fingerprint, { context.contentResolver.openInputStream(Uri.parse(record.uri!!))!! })
            f.verifyOriginal()
            assertFalse(f.handle.hasUnreleasedResources)
            scenario.moveToState(Lifecycle.State.RESUMED)
            DevelopExecution.updateConditions(allowed)
            val same = f.recheck().get(60, TimeUnit.SECONDS)
            assertEquals(record.uri, same.uri)
            assertEquals(f.beforeJpegs + 1, jpegCount())
        }
    }

    @Test fun captureBeforeQueuedAdmissionDoesNotCreateIntentOrAutomaticallyRestart() = foreground {
        Fixture().use { f ->
            f.initialize()
            val release = CountDownLatch(1)
            val entered = CountDownLatch(1)
            val barrier = AndroidAutoDevelopQueue.get(context).manualOriginalIo {
                entered.countDown(); check(release.await(60, TimeUnit.SECONDS))
            }
            try {
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                instrumentation.runOnMainSync { assertTrue(f.ui.startForegroundSave(context)) }
                await { ForegroundExports.gate.active()?.started == true }
                val capture = DevelopExecution.reserveCapture()
                DevelopExecution.releaseCapture(capture)
            } finally { release.countDown(); barrier.get(30, TimeUnit.SECONDS) }
            await { ForegroundExports.gate.active() == null }
            assertEquals(ManualOriginalTrialSavePhase.READY, f.handle.state.value.phase)
            assertNull(f.journal().read())
            assertNull(f.storage(CaptureJournalFile.MANUAL_REQUEST).read(ManualOriginalTrialRequestCodec.MAX_BYTES))
            assertEquals(f.beforeJpegs, jpegCount())
            assertFalse(f.handle.hasUnreleasedResources)
            f.verifyOriginal()
        }
    }

    private fun foreground(block: (ActivityScenario<PhotoRollActivity>) -> Unit) {
        var observation: PhotoRollCameraOwners.Owner? = null
        instrumentation.runOnMainSync {
            observation = AndroidPhotoRollCameraObservations.register { PhotoRollCameraSnapshot(false, false, false, false) }
        }
        try { ActivityScenario.launch(PhotoRollActivity::class.java).use(block) }
        finally { instrumentation.runOnMainSync { observation?.close() } }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60)
        while (!condition()) { check(System.nanoTime() < deadline) { "Synthetic export did not reach expected state" }; Thread.sleep(25) }
    }

    private inner class Fixture : AutoCloseable {
        private val resolver = context.contentResolver
        private lateinit var uri: Uri
        private lateinit var source: SharedOriginalReference
        lateinit var handle: AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle
        lateinit var ui: ManualTrialJpegSaveOwner
        private var preparation: java.util.concurrent.CompletableFuture<AndroidManualOriginalTrialSaves.ManualOriginalTrialHandle>? = null
        private var rechecking: java.util.concurrent.CompletableFuture<ManualOriginalTrialSaveState>? = null
        var screen: ActivityScenario<PhotoRollActivity>? = null
        var screenVerified = false
        private var previewCleanup: java.util.concurrent.CompletableFuture<Unit>? = null
        val name = "synthetic_export_${UUID.randomUUID()}.dng"
        val beforeJpegs = jpegCount()
        // Invoked inside use: partial initialization also has a cleanup owner.
        fun initialize(strength: Double = .6, adjustments: PhotoAdjustments = PhotoAdjustments(.2, .3)) {
            uri = requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Cinematic Camera Synthetic Tests/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
            }))
            val bytes = syntheticTrialDng()
            resolver.openOutputStream(uri, "w")!!.use { it.write(bytes) }
            check(resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) == 1)
            source = SharedOriginalReference(uri.toString(), DngContentVerifier.identify(bytes.size.toLong(), { resolver.openInputStream(uri)!! }), OriginalGrant.APP_OWNED)
            DevelopExecution.updateConditions(allowed)
            preparation = AndroidManualOriginalTrialSaves.prepare(context, source, strength, allowed, adjustments)
            handle = requireNotNull(preparation).get(60, TimeUnit.SECONDS)
            ui = ManualTrialJpegSaveOwner(handle) { ready }
        }
        fun storage(kind: CaptureJournalFile) = AndroidAtomicJournalStorage(
            File(context.noBackupFilesDir, "raw-captures/${handle.request.captureId}/renders"), handle.request.generation.toString(), kind)
        fun journal() = JpegPublicationJournal(handle.request.captureId, handle.request.generation, storage(CaptureJournalFile.JPEG_PUBLICATION))
        fun verifyOriginal() = DngContentVerifier.verify(source.fingerprint, { resolver.openInputStream(uri)!! })
        fun recheck() = handle.advance(allowed) { false }.also { rechecking = it }
        override fun close() {
            try {
                // Screen checks/readback have their own lifetime, separate from the export handle.
                screen?.moveToState(Lifecycle.State.CREATED)
                if (::ui.isInitialized) ui.cancelForegroundSave()
                // Do not cancel the future or delete input while its queued preflight could still read it.
                preparation?.let { handle = it.get(60, TimeUnit.SECONDS) }
                rechecking?.get(60, TimeUnit.SECONDS)
                if (screen != null) {
                    previewCleanup = AndroidManualOriginalTrialSaves.discardPreview(context)
                    requireNotNull(previewCleanup).get(60, TimeUnit.SECONDS)
                    // A failed UI wait cannot prove a separate checkOriginal reader has returned.
                    check(screenVerified) { "Retain source after an unconfirmed Screen read" }
                }
                await { ForegroundExports.gate.active() == null }
                if (::handle.isInitialized) {
                    check(!handle.hasUnreleasedResources) { "Retain synthetic artifacts until resources close" }
                    journal().read()?.uri?.let { check(resolver.delete(Uri.parse(it), null, null) == 1) }
                }
                if (::source.isInitialized) verifyOriginal()
                if (::uri.isInitialized) check(resolver.delete(uri, null, null) == 1)
                // Keep private journal evidence; each fixture has a unique source and never reuses it.
            } catch (failure: Throwable) {
                unsettledFixture = true
                throw IllegalStateException("Synthetic cleanup unconfirmed; retain artifacts and stop remaining tests", failure)
            }
        }
    }

    private fun jpegCount() = context.contentResolver.query(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
        arrayOf(MediaStore.Images.Media._ID), "${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaStore.Images.Media.MIME_TYPE}=?",
        arrayOf(context.packageName, "image/jpeg"), null)!!.use { it.count }

    companion object { @Volatile private var unsettledFixture = false }
}
