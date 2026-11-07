package dev.cinematic.p0.photoedit

import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.rawcapture.*
import java.io.File
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

/** Synthetic metadata in a unique private cache directory. Never accesses a photo or camera. */
class PhotoEditRecipeAndroidTest {
    @Test fun atomicRecipeSurvivesStoreRecreationAndReplacement() {
        check(InstrumentationRegistry.getArguments().getString("isolated") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "synthetic-edit-${UUID.randomUUID()}")
        val source = SharedOriginalReference("content://synthetic.test/images/1",
            OriginalFingerprint(12, "a".repeat(64)), OriginalGrant.USER_SELECTED)
        fun store() = PhotoEditRecipeStore(source, AndroidAtomicJournalStorage(root,
            ManualOriginalTrialRequests.identity(source), CaptureJournalFile.EDIT_RECIPE))
        try {
            assertNull(store().read())
            val values = PhotoEditValues(.7, 22.0, -.4)
            store().save(values)
            assertEquals(values, store().read())
            store().save(PhotoAdjustmentPolicy.defaults)
            assertEquals(PhotoAdjustmentPolicy.defaults, store().read())
            val files = root.walkTopDown().filter { it.isFile }.toList()
            assertEquals(1, files.size)
            assertEquals("edit-recipe.bin", files.single().name)
            assertTrue(files.single().length() <= PhotoEditRecipeCodec.MAX_BYTES)
        } finally { root.deleteRecursively() }
    }
}
