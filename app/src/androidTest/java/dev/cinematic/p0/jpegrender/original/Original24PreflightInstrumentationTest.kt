package dev.cinematic.p0.jpegrender.original

import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.cinematic.p0.testing.writeSyntheticTrialDng
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Small private synthetic metadata fixtures only. No camera, shared row or full native decode. */
@RunWith(AndroidJUnit4::class)
class Original24PreflightInstrumentationTest {
    @Test fun existingNativeParserReportsExpectedRotationWithoutMovingBorrowedDescriptor() {
        for (orientation in listOf(1, 6)) withSource(synthetic(orientation)) { fd ->
            Os.lseek(fd.fileDescriptor, 7, OsConstants.SEEK_SET)
            val result = Original24Preflight.inspect(fd.fd, CancellationSignal())
            assertEquals(64, result.rawWidth)
            assertEquals(48, result.rawHeight)
            assertEquals(0, result.cropLeft)
            assertEquals(0, result.cropTop)
            assertEquals(if (orientation == 6) 48 else 64, result.outputWidth)
            assertEquals(if (orientation == 6) 64 else 48, result.outputHeight)
            assertEquals(7L, Os.lseek(fd.fileDescriptor, 0, OsConstants.SEEK_CUR))
        }
    }

    @Test fun existingNativeParserRejectsCyclesAmbiguousRawIfdsAndDuplicateCriticalTags() {
        val cases = listOf(
            rewrite(nextCycle = true),
            rewrite(extras = listOf(Field(330, 4, 1, longs(-1)))), // Self-referencing SubIFD.
            rewrite(extras = listOf(Field(330, 4, 1, longs(8)))), // Second CFA raw IFD.
            rewrite(extras = listOf(Field(256, 4, 1, longs(64)))),
        )
        for (bytes in cases) withSource(bytes) { fd ->
            assertThrows(IOException::class.java) { Original24Preflight.inspect(fd.fd, CancellationSignal()) }
        }
    }

    @Test fun integralDefaultCropReportsAbsoluteOffsetsAlongWithOutputDimensions() {
        val bytes = rewrite(extras = listOf(Field(50719, 4, 2, longs(2, 2)), Field(50720, 4, 2, longs(60, 44))))
        withSource(bytes) { fd ->
            val geometry = Original24Preflight.inspect(fd.fd, CancellationSignal())
            assertEquals(64, geometry.rawWidth)
            assertEquals(48, geometry.rawHeight)
            assertEquals(2, geometry.cropLeft)
            assertEquals(2, geometry.cropTop)
            assertEquals(60, geometry.outputWidth)
            assertEquals(44, geometry.outputHeight)
        }
    }

    @Test fun existingNativeParserRejectsFractionalCropNonunitScaleAndUnknownUserCrop() {
        val cases = listOf(
            rewrite(extras = listOf(Field(50719, 5, 2, longs(1, 2, 0, 1)))),
            rewrite(extras = listOf(Field(50718, 5, 2, longs(2, 1, 1, 1)))),
            rewrite(extras = listOf(Field(51125, 5, 4, longs(0, 1, 0, 1, 1, 1, 1, 1)))),
            rewrite(extras = listOf(Field(50829, 4, 4, longs(0, 0, 49, 64)))),
            rewrite(extras = listOf(Field(50720, 4, 2, longs(65, 48)))),
        )
        for (bytes in cases) withSource(bytes) { fd ->
            assertThrows(IOException::class.java) { Original24Preflight.inspect(fd.fd, CancellationSignal()) }
        }
    }

    @Test fun cancellationAndNonclassicTiffAreRejectedBeforeOwnerAdmission() {
        withSource(synthetic(1)) { fd ->
            assertThrows(IOException::class.java) { Original24Preflight.inspect(fd.fd, CancellationSignal().apply { cancel() }) }
        }
        val bigTiff = synthetic(1).also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(2, 43) }
        withSource(bigTiff) { fd ->
            assertThrows(IOException::class.java) { Original24Preflight.inspect(fd.fd, CancellationSignal()) }
        }
    }

    private fun withSource(bytes: ByteArray, action: (ParcelFileDescriptor) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName == "dev.cinematic.p0.original24probe")
        val file = File.createTempFile("preflight-synthetic-", ".dng", context.cacheDir)
        try {
            file.writeBytes(bytes)
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use(action)
        } finally { assertTrue(file.delete()) }
    }

    private fun synthetic(orientation: Int) = ByteArrayOutputStream().also {
        writeSyntheticTrialDng(it, 64, 48, orientation)
    }.toByteArray()

    private data class Field(val tag: Int, val type: Int, val count: Int, val data: ByteArray)
    private fun longs(vararg values: Int) = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        .apply { values.forEach { putInt(it) } }.array()

    /** Append a new root, preserving the synthetic RAW/data offsets and its original IFD at 8. */
    private fun rewrite(extras: List<Field> = emptyList(), nextCycle: Boolean = false): ByteArray {
        val original = synthetic(1)
        val old = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN)
        val count = old.getShort(8).toInt() and 0xffff
        val entries = (0 until count).map { original.copyOfRange(10 + it * 12, 22 + it * 12) }.toMutableList()
        val root = original.size
        val totalEntries = count + extras.size
        var extraOffset = root + 2 + totalEntries * 12 + 4
        val output = ByteBuffer.allocate(extraOffset + extras.sumOf { if (it.data.size > 4) it.data.size else 0 })
            .order(ByteOrder.LITTLE_ENDIAN)
        output.put(original).putInt(4, root)
        for (field in extras) {
            val entry = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(field.tag.toShort()).putShort(field.type.toShort()).putInt(field.count)
            if (field.data.size <= 4) {
                if (field.tag == 330 && ByteBuffer.wrap(field.data).order(ByteOrder.LITTLE_ENDIAN).int == -1)
                    entry.putInt(root)
                else { entry.put(field.data); repeat(4 - field.data.size) { entry.put(0) } }
            } else {
                entry.putInt(extraOffset)
                field.data.copyInto(output.array(), extraOffset)
                extraOffset += field.data.size
            }
            entries += entry.array()
        }
        output.position(root)
        output.putShort(totalEntries.toShort())
        for (entry in entries.sortedBy { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff })
            output.put(entry)
        output.putInt(if (nextCycle) root else 0)
        return output.array()
    }
}
