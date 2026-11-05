package dev.cinematic.p0.photoroll

import android.graphics.Bitmap
import android.graphics.Color
import android.os.CancellationSignal
import android.os.OperationCanceledException
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Ownership and failure boundaries use generated pixels only; no user image or decoder mock. */
@RunWith(AndroidJUnit4::class)
class PrivateTrialDisplayCopyTest {
    @Test fun optionalComparisonAllocationFailureKeepsIndependentAfter() = withPair { after, before ->
        for (allocation in listOf<(Bitmap) -> Bitmap?>({ null }, { throw OutOfMemoryError("synthetic") })) {
            val result = copyPrivateTrialDisplay(after, before, CancellationSignal(), allocation)
            try {
                assertNull(result.comparison)
                assertNotSame(after, result.after)
                assertFalse(result.after.isRecycled)
                assertEquals(Color.BLUE, result.after.getPixel(0, 0))
                assertFalse(after.isRecycled); assertFalse(before.isRecycled)
            } finally { result.after.recycle() }
        }
    }

    @Test fun cancellationDuringComparisonCopyRecyclesPartialCopyWithoutTouchingQueueImages() = withPair { after, before ->
        val cancellation = CancellationSignal()
        var copied: Bitmap? = null
        try {
            copyPrivateTrialDisplay(after, before, cancellation) {
                it.copy(Bitmap.Config.ARGB_8888, false).also { bitmap ->
                    copied = bitmap
                    cancellation.cancel()
                }
            }
            fail("Cancelled pair must not reach the UI")
        } catch (_: OperationCanceledException) {
            assertTrue(requireNotNull(copied).isRecycled)
            assertFalse(after.isRecycled); assertFalse(before.isRecycled)
        }
    }

    @Test fun copiedPairIsIndependentAndSameGeometry() = withPair { after, before ->
        val result = copyPrivateTrialDisplay(after, before, CancellationSignal())
        try {
            val comparison = requireNotNull(result.comparison)
            assertNotSame(after, result.after); assertNotSame(before, comparison)
            assertEquals(result.after.width, comparison.width); assertEquals(result.after.height, comparison.height)
            assertEquals(Color.BLUE, result.after.getPixel(0, 0)); assertEquals(Color.RED, comparison.getPixel(0, 0))
            after.recycle(); before.recycle()
            assertFalse(result.after.isRecycled); assertFalse(comparison.isRecycled)
        } finally { result.after.recycle(); result.comparison?.recycle() }
    }

    private inline fun withPair(block: (Bitmap, Bitmap) -> Unit) {
        val after = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.BLUE) }
        val before = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888).also { it.eraseColor(Color.RED) }
        try { block(after, before) } finally { after.recycle(); before.recycle() }
    }
}
