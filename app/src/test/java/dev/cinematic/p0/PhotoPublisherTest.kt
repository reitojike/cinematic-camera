package dev.cinematic.p0

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PhotoPublisherTest {
    @get:Rule val temp = TemporaryFolder()

    private fun source(name: String, format: PhotoFormat, bytes: ByteArray = byteArrayOf(0, 1, -1, 7)) =
        PhotoSource(temp.newFile(name).also { it.writeBytes(bytes) }, format)

    @Test fun insertFailureHasNoOutstandingStreams() {
        val publisher = PhotoPublisher(Store().apply { createFailureAt = 0 })
        assertThrows(PhotoPublishException::class.java) { publisher.publish(listOf(source("insert.jpg", PhotoFormat.JPEG))) }
        assertTrue(publisher.resourcesClosed)
    }

    @Test fun writeFailureWithSuccessfulClosesReleasesCaptureResources() {
        val publisher = PhotoPublisher(Store().apply { writeFailure = true })
        assertThrows(PhotoPublishException::class.java) { publisher.publish(listOf(source("write.jpg", PhotoFormat.JPEG))) }
        assertTrue(publisher.resourcesClosed)
    }

    @Test fun failedCloseRetainsUnknownResourcesAndRejectsReuse() {
        val publisher = PhotoPublisher(Store().apply { closeFailure = true })
        val sources = listOf(source("close.jpg", PhotoFormat.JPEG))
        assertThrows(PhotoPublishException::class.java) { publisher.publish(sources) }
        assertFalse(publisher.resourcesClosed)
        assertThrows(IllegalStateException::class.java) { publisher.publish(sources) }
    }

    private class Store : ImageStore {
        data class Row(val name: String, val mime: String, val path: String,
            val bytes: ByteArrayOutputStream = ByteArrayOutputStream(), var closed: Boolean = false)
        val rows = linkedMapOf<String, Row>()
        val deleted = mutableListOf<String>()
        val published = mutableListOf<String>()
        var createFailureAt = -1
        var openFailure = false
        var writeFailure = false
        var closeFailure = false
        var publishFailureAt = -1
        var deleteFailure = false
        override fun createPending(name: String, mimeType: String, relativePath: String): String {
            if (rows.size == createFailureAt) throw IOException("insert failure")
            val uri = "image:${rows.size}"
            rows[uri] = Row(name, mimeType, relativePath)
            return uri
        }
        override fun openOutput(uri: String): OutputStream {
            if (openFailure) throw IOException("open failure")
            val row = rows.getValue(uri)
            return object : OutputStream() {
                override fun write(value: Int) {
                    if (writeFailure) throw IOException("write failure")
                    row.bytes.write(value)
                }
                override fun close() {
                    row.closed = true
                    if (closeFailure) throw IOException("close failure")
                }
            }
        }
        override fun publish(uri: String) {
            assertTrue("Every stream must close before publish", rows.values.all { it.closed })
            if (published.size == publishFailureAt) throw IOException("publish failure")
            published += uri
        }
        override fun delete(uri: String) {
            deleted += uri
            if (deleteFailure) throw IOException("delete failure")
        }
    }

    @Test fun jpegAndDngPreserveBytesMimeAndFolderBeforePublish() {
        val raw = source("sample.dng", PhotoFormat.DNG)
        val jpeg = source("sample.jpg", PhotoFormat.JPEG, byteArrayOf(9, 8, 0, -128))
        val store = Store()
        val saved = PhotoPublisher(store).publish(listOf(raw, jpeg))
        assertEquals(listOf("image/x-adobe-dng", "image/jpeg"), store.rows.values.map { it.mime })
        assertTrue(store.rows.values.all { it.path == "Pictures/Cinematic Camera/" })
        assertArrayEquals(raw.file.readBytes(), store.rows.getValue(saved[0].uri).bytes.toByteArray())
        assertArrayEquals(jpeg.file.readBytes(), store.rows.getValue(saved[1].uri).bytes.toByteArray())
        assertEquals(listOf(4L, 4L), saved.map { it.bytes })
        assertEquals(2, store.published.size)
        assertTrue(store.deleted.isEmpty())
    }

    @Test fun secondInsertFailureRollsBackFirstRowAndPreservesSources() {
        val raw = source("raw.dng", PhotoFormat.DNG)
        val jpeg = source("jpeg.jpg", PhotoFormat.JPEG)
        val store = Store().apply { createFailureAt = 1 }
        assertThrows(PhotoPublishException::class.java) { PhotoPublisher(store).publish(listOf(raw, jpeg)) }
        assertEquals(listOf("image:0"), store.deleted)
        assertTrue(store.published.isEmpty())
        assertEquals(4L, raw.file.length()); assertEquals(4L, jpeg.file.length())
    }

    @Test fun streamOpenFailureDeletesTheInsertedRow() {
        val photo = source("image.jpg", PhotoFormat.JPEG)
        val store = Store().apply { openFailure = true }
        assertThrows(PhotoPublishException::class.java) { PhotoPublisher(store).publish(listOf(photo)) }
        assertEquals(listOf("image:0"), store.deleted)
        assertTrue(store.published.isEmpty())
    }

    @Test fun writeAndCloseFailuresNeverPublishAndCloseTheStream() {
        val photo = source("image.dng", PhotoFormat.DNG)
        listOf(true, false).forEach { failWrite ->
            val store = Store().apply { writeFailure = failWrite; closeFailure = !failWrite }
            assertThrows(PhotoPublishException::class.java) { PhotoPublisher(store).publish(listOf(photo)) }
            assertTrue(store.rows.values.all { it.closed })
            assertEquals(1, store.deleted.size)
            assertTrue(store.published.isEmpty())
        }
    }

    @Test fun secondPublishFailureRollsBackEvenAlreadyPublishedRow() {
        val store = Store().apply { publishFailureAt = 1 }
        assertThrows(PhotoPublishException::class.java) {
            PhotoPublisher(store).publish(listOf(source("r.dng", PhotoFormat.DNG), source("j.jpg", PhotoFormat.JPEG)))
        }
        assertEquals(listOf("image:1", "image:0"), store.deleted)
    }

    @Test fun cleanupFailureIsReportedAndOtherRowsAreStillAttempted() {
        val store = Store().apply { publishFailureAt = 0; deleteFailure = true }
        val error = assertThrows(PhotoPublishException::class.java) {
            PhotoPublisher(store).publish(listOf(source("r.dng", PhotoFormat.DNG), source("j.jpg", PhotoFormat.JPEG)))
        }
        assertEquals(2, error.cleanupFailures)
        assertEquals(2, store.deleted.size)
    }

    @Test fun emptyCameraFileCreatesNoSharedRow() {
        val store = Store()
        assertThrows(IllegalArgumentException::class.java) {
            PhotoPublisher(store).publish(listOf(source("empty.jpg", PhotoFormat.JPEG, byteArrayOf())))
        }
        assertTrue(store.rows.isEmpty())
    }
}
