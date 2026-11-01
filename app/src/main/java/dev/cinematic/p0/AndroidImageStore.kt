package dev.cinematic.p0

import android.content.ContentResolver
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.IOException
import java.io.OutputStream

@RequiresApi(Build.VERSION_CODES.Q)
class AndroidImageStore(private val resolver: ContentResolver) : ImageStore {
    override fun createPending(name: String, mimeType: String, relativePath: String): String {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, relativePath)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        return resolver.insert(collection, values)?.toString()
            ?: throw IOException("MediaStore insert returned null")
    }

    override fun openOutput(uri: String): OutputStream =
        resolver.openOutputStream(Uri.parse(uri), "w") ?: throw IOException("MediaStore stream unavailable")

    override fun publish(uri: String) {
        val values = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
        if (resolver.update(Uri.parse(uri), values, null, null) != 1) {
            throw IOException("MediaStore pending update did not affect one image")
        }
    }

    override fun delete(uri: String) { resolver.delete(Uri.parse(uri), null, null) }
}
