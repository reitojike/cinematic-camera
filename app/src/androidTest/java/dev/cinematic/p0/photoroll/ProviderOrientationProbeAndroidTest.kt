package dev.cinematic.p0.photoroll

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.CancellationSignal
import android.provider.MediaStore
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** 合成DNGだけで既存provider経路とAndroidXの向き読込を対照する。製品へは未接続。 */
@RunWith(AndroidJUnit4::class)
class ProviderOrientationProbeAndroidTest {
    @Test fun sameOrientationCanYieldAlreadyRotatedOrUnrotatedProviderBitmap() {
        val resolver = InstrumentationRegistry.getInstrumentation().targetContext.contentResolver
        for (embedded in listOf(false, true)) for (orientation in listOf(1, 3, 6, 8)) {
            val data = if (embedded) syntheticRollDngWithThumbnail(orientation) else syntheticRollDng(256, 192, orientation)
            val uri = requireNotNull(resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "synthetic-provider-${System.nanoTime()}.dng")
                put(MediaStore.MediaColumns.MIME_TYPE, "image/x-adobe-dng")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/CinematicSyntheticProbe")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }))
            var image: Bitmap? = null
            try {
                resolver.openOutputStream(uri, "w")!!.use { it.write(data) }
                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                val signal = CancellationSignal()
                val observed = resolver.openFileDescriptor(uri, "r", signal)!!.use {
                    ExifInterface(it.fileDescriptor).getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)
                }
                assertEquals("AndroidXは展開せずOrientationを読む", orientation, observed)
                image = resolver.loadThumbnail(uri, Size(256, 256), signal)
                println("PROVIDER_ORIENTATION embedded=$embedded orientation=$observed width=${image.width} height=${image.height}")
                assertTrue(image.width > 0 && image.height > 0)
                val providerImage = image
                image = correctRollDngThumbnail(providerImage, signal) { resolver.openFileDescriptor(uri, "r", signal) }
                if (orientation in listOf(6, 8)) {
                    assertTrue("最終表示は縦長", image.height > image.width)
                    if (embedded) assertSame("回転済みproviderを二重回転しない", providerImage, image)
                    else assertTrue("未回転像だけ置換", providerImage.isRecycled)
                } else assertSame("180度は対象外", providerImage, image)
                val after = resolver.openInputStream(uri)!!.use { it.readBytes() }
                assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(data), MessageDigest.getInstance("SHA-256").digest(after))
            } finally {
                image?.recycle()
                assertEquals("作成した合成データだけを後片付け", 1, resolver.delete(uri, null, null))
            }
        }
    }
}
