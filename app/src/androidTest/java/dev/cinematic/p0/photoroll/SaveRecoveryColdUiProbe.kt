package dev.cinematic.p0.photoroll

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.jpegrender.original.Original24Request
import dev.cinematic.p0.rawcapture.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*

/** Uses the ordinary Compose screen and service path, after host-proven process death.
 * No replacement renderer, owner, notification or UI is installed by this probe. */
internal object SaveRecoveryColdUiProbe {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    fun check(app: Context, old: Original24Request, name: String, boundary: String, publishExplicitly: Boolean) {
        require(boundary in setOf("intent", "b-cursor", "unknown-complete", "unknown-public", "unknown-partial"))
        check(Build.HARDWARE in setOf("ranchu", "goldfish") && app.packageName == "dev.cinematic.p0")
        val unknown = if (boundary.startsWith("unknown-")) UnknownEvidence(app, old) else null
        assertNull(AndroidManualOriginalTrialSaves.knownPrepared(old.source))
        cameraRollReadPermissions(Build.VERSION.SDK_INT).forEach {
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, it)
        }
        if (Build.VERSION.SDK_INT >= 33)
            instrumentation.uiAutomation.grantRuntimePermission(app.packageName, android.Manifest.permission.POST_NOTIFICATIONS)
        var camera: PhotoRollCameraOwners.Owner? = null
        instrumentation.runOnMainSync {
            camera = AndroidPhotoRollCameraObservations.register { PhotoRollCameraSnapshot(false, false, false, false) }
        }
        val sawForegroundOwner = AtomicBoolean(false)
        val observer = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val watch = observer.launch(start = CoroutineStart.UNDISPATCHED) {
            ForegroundExports.changes.collect {
                if (ForegroundExports.gate.active() != null) sawForegroundOwner.set(true)
            }
        }
        try {
            ActivityScenario.launch(PhotoRollActivity::class.java).use {
                click(name)
                val expected = if (boundary == "b-cursor") old.copy(generation = old.generation + 1) else old
                await("cold metadata and controls") {
                    AndroidManualOriginalTrialSaves.knownPrepared(old.source)?.request == expected &&
                        action(if (boundary == "b-cursor") "JPEGを保存" else "保存結果を確認・保存し直す") != null
                }
                val handle = requireNotNull(AndroidManualOriginalTrialSaves.knownPrepared(old.source))
                assertEquals(old.strength, handle.appliedStrength, 0.0) // draft is deliberately .35
                assertEquals(expected, handle.request)
                val requireDisplayed = boundary == "b-cursor"
                await("fixed strength visible") { strengthVisible(old.strength, requireDisplayed) }
                val before = intent(app, expected)
                val idleUntil = SystemClock.elapsedRealtime() + 1500
                while (SystemClock.elapsedRealtime() < idleUntil) {
                    instrumentation.waitForIdleSync()
                    assertEquals(expected, AndroidManualOriginalTrialSaves.knownPrepared(old.source)?.request)
                    assertArrayEquals(before, intent(app, expected))
                    if (unknown == null) assertNull(publication(app, expected)) else unknown.unchanged(pending = boundary != "unknown-public")
                    assertNull(ForegroundExports.gate.active())
                    assertTrue("UI must keep the fixed strength without claiming a held preview was rendered",
                        strengthVisible(old.strength, requireDisplayed))
                    Thread.sleep(25)
                }
                assertFalse(sawForegroundOwner.get())
                if (boundary == "intent" || unknown != null) {
                    click("保存結果を確認・保存し直す"); click("もう一度確認")
                    val positive = boundary in setOf("unknown-complete", "unknown-public")
                    await("recheck result through product owner") {
                        if (positive) handle.state.value.phase == ManualOriginalTrialSavePhase.PUBLISHED &&
                            action("保存したJPEGを照合して開く") != null
                        else action("保存結果を確認・保存し直す") != null
                    }
                    assertArrayEquals(before, intent(app, expected))
                    if (unknown == null) {
                        assertNull(publication(app, expected))
                        assertEquals(ManualOriginalTrialSavePhase.HOLD, handle.state.value.phase)
                    } else {
                        unknown.unchanged(pending = !positive)
                        assertEquals(if (positive) ManualOriginalTrialSavePhase.PUBLISHED else ManualOriginalTrialSavePhase.UNCONFIRMED,
                            handle.state.value.phase)
                        if (!positive) assertEquals(ManualOriginalTrialSaveReason.PENDING_BYTES_DIFFER, handle.state.value.reason)
                        assertFalse(handle.hasUnreleasedResources)
                        val recovery = storage(app, old, CaptureJournalFile.JPEG_RECOVERY)
                            .read(JpegPublicationCodec.MAX_BYTES)?.let(JpegPublicationCodec::decode)
                        if (positive) {
                            assertEquals(JpegPublicationPhase.PUBLISHED, requireNotNull(recovery).phase)
                            assertEquals(unknown.record.uri, recovery.uri)
                            assertEquals(unknown.record.target, recovery.target)
                            assertEquals(unknown.record.fingerprint, recovery.fingerprint)
                        } else assertNull(recovery)
                        assertNull(storage(app, old, CaptureJournalFile.EXPLICIT_RECOVERY_EXPORT).read(SaveRecoveryCodec.MAX_BYTES))
                    }
                    assertFalse(sawForegroundOwner.get())
                }
                if (publishExplicitly) {
                    if (boundary == "intent" || boundary == "unknown-partial") {
                        click("保存結果を確認・保存し直す")
                        click("重複の可能性はあるが保存し直す")
                    } else click("JPEGを保存")
                    await("explicit FGS publication", 180_000) {
                        AndroidManualOriginalTrialSaves.knownPrepared(old.source)?.state?.value?.phase == ManualOriginalTrialSavePhase.PUBLISHED &&
                            ForegroundExports.gate.active() == null
                    }
                    assertTrue("Normal service path must be observed", sawForegroundOwner.get())
                    val next = requireNotNull(AndroidManualOriginalTrialSaves.knownPrepared(old.source))
                    assertEquals(old.generation + 1, next.request.generation)
                    assertEquals(old.recipe, next.request.recipe)
                    assertFalse(next.hasUnreleasedResources)
                    val result = requireNotNull(publication(app, next.request))
                    assertEquals(JpegPublicationPhase.PUBLISHED, result.phase)
                    assertEquals(next.request.source, result.render.request.source)
                    DngContentVerifier.verify(result.fingerprint,
                        { requireNotNull(app.contentResolver.openInputStream(android.net.Uri.parse(result.uri))) })
                    if (unknown != null) {
                        assertEquals("Only partial UNKNOWN can request B here", "unknown-partial", boundary)
                        assertEquals(old.source, next.request.source)
                        assertEquals(old.strength, next.request.strength, 0.0)
                        assertEquals(old.adjustments, next.request.adjustments)
                        assertNotEquals(unknown.record.uri, result.uri)
                        val consentStorage = storage(app, old, CaptureJournalFile.EXPLICIT_RECOVERY_EXPORT)
                        val consentBytes = requireNotNull(consentStorage.read(SaveRecoveryCodec.MAX_BYTES))
                        val consent = SaveRecoveryCodec.readExport(consentBytes)
                        assertEquals(old, consent.previous)
                        assertEquals(unknown.record, consent.previousPublication)
                        assertEquals(next.request, consent.request)
                        val authority = ExplicitRecoveryExports(consentStorage)
                        assertFalse(authority.oldMayRun())
                        assertTrue(authority.newMayRun(consent))
                        // Repeated acceptance after UI/FGS completion must return this exact request.
                        val duplicate1 = handle.requestExplicitRecovery { false }
                        val duplicate2 = handle.requestExplicitRecovery { false }
                        assertSame(next, duplicate1.get(30, java.util.concurrent.TimeUnit.SECONDS))
                        assertSame(next, duplicate2.get(30, java.util.concurrent.TimeUnit.SECONDS))
                        val allowed = AutoDevelopConditions(true, false, false, false, true, true, true, true, previewMemoryAvailable = true)
                        assertThrows(java.util.concurrent.ExecutionException::class.java) {
                            handle.advance(allowed) { false }.get(30, java.util.concurrent.TimeUnit.SECONDS)
                        }
                        assertEquals(ManualOriginalTrialSavePhase.PUBLISHED,
                            next.advance(allowed) { false }.get(30, java.util.concurrent.TimeUnit.SECONDS).phase)
                        assertArrayEquals(consentBytes, consentStorage.read(SaveRecoveryCodec.MAX_BYTES))
                        assertFalse(handle.hasUnreleasedResources)
                        assertFalse(next.hasUnreleasedResources)
                        unknown.unchanged(pending = true, addedUri = requireNotNull(result.uri))
                    }
                }
            }
            GpuPhotoRollRetiredOwners.releaseBeforeOpen()
        } finally {
            watch.cancel(); observer.cancel()
            instrumentation.runOnMainSync { camera?.close() }
        }
    }
    /** Read-only evidence for one synthetic capture, including pending rows. No product seams. */
    private class UnknownEvidence(val app: Context, val old: Original24Request) {
        private val intentBytes = requireNotNull(intent(app, old))
        private val journalBytes = requireNotNull(storage(app, old, CaptureJournalFile.JPEG_PUBLICATION).read(JpegPublicationCodec.MAX_BYTES))
        val record = JpegPublicationCodec.decode(journalBytes)
        private val rows = MediaStoreJpegRows(app)
        private val initialRow = rows.find(record.target).single()
        private val bytes = rows.openRead(record.target, requireNotNull(record.uri), requireNotNull(initialRow.pending)).use { it.readBytes() }
        private val initialUris = captureUris()
        init {
            assertEquals(JpegPublicationPhase.UNKNOWN, record.phase)
            assertEquals(setOf(record.uri), initialUris)
            assertEquals(record.uri, initialRow.uri)
            noOldProducer()
        }
        private fun noOldProducer() {
            assertNull(storage(app, old, CaptureJournalFile.MANUAL_ATTEMPT).read(ManualTrialAttemptCodec.MAX_BYTES))
            val root = RenderedJpegFiles(File(app.noBackupFilesDir, "raw-captures"), old.captureId, old.generation).generationRoot()
            assertFalse("Recovery must not create old-generation producer output", File(root, "output.jpg").exists())
        }
        fun unchanged(pending: Boolean, addedUri: String? = null) {
            assertArrayEquals(intentBytes, intent(app, old))
            assertArrayEquals(journalBytes, storage(app, old, CaptureJournalFile.JPEG_PUBLICATION).read(JpegPublicationCodec.MAX_BYTES))
            val row = rows.find(record.target).single()
            assertEquals(initialRow.copy(pending = pending), row)
            assertArrayEquals(bytes, rows.openRead(record.target, requireNotNull(record.uri), pending).use { it.readBytes() })
            assertEquals(initialUris + listOfNotNull(addedUri), captureUris())
            assertEquals(old.source.fingerprint, DngContentVerifier.verify(old.source.fingerprint,
                { requireNotNull(app.contentResolver.openInputStream(android.net.Uri.parse(old.source.uri))) }))
            noOldProducer()
        }
        private fun captureUris(): Set<String> {
            val collection = android.provider.MediaStore.Images.Media.getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val prefix = "CINEMATIC_${old.captureId}_R"
            val columns = arrayOf(android.provider.MediaStore.MediaColumns._ID, android.provider.MediaStore.MediaColumns.DISPLAY_NAME)
            val selection = "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("$prefix%")
            val cursor = if (Build.VERSION.SDK_INT >= 30) {
                app.contentResolver.query(collection, columns, android.os.Bundle().apply {
                    putInt(android.provider.MediaStore.QUERY_ARG_MATCH_PENDING, android.provider.MediaStore.MATCH_INCLUDE)
                    putInt(android.provider.MediaStore.QUERY_ARG_MATCH_TRASHED, android.provider.MediaStore.MATCH_INCLUDE)
                    putString(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                    putStringArray(android.content.ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
                }, null)
            } else {
                @Suppress("DEPRECATION")
                val includingPending = android.provider.MediaStore.setIncludePending(collection)
                app.contentResolver.query(includingPending, columns, selection, selectionArgs, null)
            }
            return requireNotNull(cursor).use {
                buildSet {
                    while (it.moveToNext()) if (it.getString(1).startsWith(prefix))
                        add(android.content.ContentUris.withAppendedId(collection, it.getLong(0)).toString())
                }
            }
        }
    }
    private fun storage(app: Context, request: Original24Request, kind: CaptureJournalFile): AtomicJournalStorage {
        val root = RenderedJpegFiles(File(app.noBackupFilesDir, "raw-captures"), request.captureId, request.generation).generationRoot()
        return AndroidAtomicJournalStorage(requireNotNull(root.parentFile), request.generation.toString(), kind)
    }
    private fun intent(app: Context, request: Original24Request) = storage(app, request, CaptureJournalFile.MANUAL_REQUEST).read(ManualOriginalTrialRequestCodec.MAX_BYTES)
    private fun publication(app: Context, request: Original24Request) = storage(app, request, CaptureJournalFile.JPEG_PUBLICATION)
        .read(JpegPublicationCodec.MAX_BYTES)?.let(JpegPublicationCodec::decode)
    private fun strengthVisible(value: Double, requireDisplayed: Boolean): Boolean {
        // Inspect the actual disabled/enabled strength control, not the immutable backend handle.
        fun texts(node: AccessibilityNodeInfo): List<String> = buildList {
            node.text?.toString()?.let { addAll(it.split('\n')) }
            for (i in 0 until node.childCount) node.getChild(i)?.let { addAll(texts(it)) }
        }
        fun find(node: AccessibilityNodeInfo?): Boolean {
            if (node == null || !node.refresh() || !node.isVisibleToUser) return false
            val own = node.text?.toString()?.split('\n').orEmpty()
            if ("STRENGTH" in own) {
                // Compose may merge the button's text; otherwise use the immediate text column.
                val labels = if (own.size > 1) own else node.parent?.let(::texts).orEmpty()
                return trialStrengthLabel(value) in labels && (!requireDisplayed || ("表示中" in labels &&
                    labels.none { it.contains("未反映") || it.startsWith("表示中 ") }))
            }
            for (i in 0 until node.childCount) if (find(node.getChild(i))) return true
            return false
        }
        instrumentation.uiAutomation.clearCache()
        return find(instrumentation.uiAutomation.rootInActiveWindow)
    }
    private fun action(label: String): AccessibilityNodeInfo? {
        fun find(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
            if (node == null || !node.refresh()) return null
            if (node.text?.toString() == label || node.contentDescription?.toString() == label) return node
            for (i in 0 until node.childCount) find(node.getChild(i))?.let { return it }
            return null
        }
        instrumentation.uiAutomation.clearCache()
        var node = find(instrumentation.uiAutomation.rootInActiveWindow)
        while (node != null && !node.isClickable && node.isEnabled) node = node.parent
        return node?.takeIf { it.refresh() && it.isVisibleToUser && it.isClickable && it.isEnabled }
    }
    private fun click(label: String) {
        var node: AccessibilityNodeInfo? = null
        await(label) { action(label).also { node = it } != null }
        assertTrue(requireNotNull(node).performAction(AccessibilityNodeInfo.ACTION_CLICK))
        instrumentation.waitForIdleSync()
    }
    private fun await(label: String, timeout: Long = 60_000, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeout
        do {
            instrumentation.waitForIdleSync()
            if (condition()) return
            Thread.sleep(25)
        } while (SystemClock.elapsedRealtime() < until)
        error("Recovery UI did not reach: $label")
    }
}
