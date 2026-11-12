package dev.cinematic.p0.rawcapture

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Shared capture/import adapter. Import discard is restricted to its verified pending row. */
@RequiresApi(29)
internal class MediaStoreDngPendingRows(context: Context) : DngHandoffRows, DngImportRows {
    private data class Target(val displayName: String, val relativePath: String, val mimeType: String,
        val volume: String = "external_primary") {
        fun accepts(row: DngPendingRow, pending: Boolean) = isDngRowUri(row.uri) && row.ownedByApp &&
            row.pending == pending && row.trashed == false && row.displayName == displayName &&
            row.relativePath == relativePath && row.mimeType == mimeType && row.volume == volume
    }
    private fun DngPublicationTarget.media() = Target(displayName, relativePath, mimeType, volume)
    private fun DngImportTarget.media() = Target(name, path, mime)
    override fun insert(target: DngPublicationTarget) = insertRow(target.media())
    override fun find(target: DngPublicationTarget) = findRows(target.media())
    override fun openRead(target: DngPublicationTarget, uri: String, pending: Boolean) = readRowStream(target.media(), uri, pending)
    override fun openOutput(target: DngPublicationTarget, uri: String) = outputRow(target.media(), uri)
    override fun publish(target: DngPublicationTarget, uri: String) = publishRow(target.media(), uri)
    override fun insert(target: DngImportTarget) = insertRow(target.media())
    override fun find(target: DngImportTarget) = findRows(target.media())
    override fun openRead(target: DngImportTarget, uri: String, pending: Boolean) = readRowStream(target.media(), uri, pending)
    override fun openPendingOutput(target: DngImportTarget, uri: String) = outputRow(target.media(), uri)
    override fun publish(target: DngImportTarget, uri: String) = publishRow(target.media(), uri)
    override fun discardPending(target: DngImportTarget, uri: String) {
        checkRow(target.media(), uri, true)
        val selection = "${MediaStore.MediaColumns.IS_PENDING}=1 AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND " +
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
            "${MediaStore.MediaColumns.MIME_TYPE}=? AND ${MediaStore.MediaColumns.VOLUME_NAME}=? AND ${MediaStore.MediaColumns.IS_TRASHED}=0"
        val args = arrayOf(packageName, target.name, target.path, target.mime, "external_primary")
        val count = if (Build.VERSION.SDK_INT >= 30) resolver.delete(Uri.parse(uri), Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
        }) else throw IOException("Import requires Android 11 or later")
        if (count != 1) throw IOException("Pending import discard unconfirmed")
    }
    private val resolver = context.applicationContext.contentResolver
    private val packageName = context.applicationContext.packageName
    private val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun insertRow(target: Target): String? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, target.displayName)
            put(MediaStore.MediaColumns.RELATIVE_PATH, target.relativePath)
            put(MediaStore.MediaColumns.MIME_TYPE, target.mimeType)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        return resolver.insert(collection, values)?.toString()
    }

    private fun findRows(target: Target): List<DngPendingRow> = query(
        collection,
        "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.MIME_TYPE}=?",
        arrayOf(target.displayName, target.relativePath, target.mimeType),
    )

    override fun inspect(uri: String): DngPendingRow? {
        if (!isDngRowUri(uri)) throw IOException("Unexpected DNG row URI")
        val rows = query(Uri.parse(uri), null, null)
        if (rows.size > 1) throw IOException("Ambiguous DNG row URI")
        return rows.singleOrNull()
    }

    private fun readRowStream(target: Target, uri: String, pending: Boolean): InputStream {
        checkRow(target, uri, pending)
        return resolver.openInputStream(Uri.parse(uri)) ?: throw IOException("DNG read stream unavailable")
    }

    private fun outputRow(target: Target, uri: String): OutputStream {
        checkRow(target, uri, true)
        // Metadata check and FD open cannot atomically exclude external/privileged changes.
        return resolver.openOutputStream(Uri.parse(uri), "wt") ?: throw IOException("DNG write stream unavailable")
    }

    private fun publishRow(target: Target, uri: String) {
        checkRow(target, uri, true)
        val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
        val selection = "${MediaStore.MediaColumns.IS_PENDING}=? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME}=? AND " +
            "${MediaStore.MediaColumns.DISPLAY_NAME}=? AND ${MediaStore.MediaColumns.RELATIVE_PATH}=? AND " +
            "${MediaStore.MediaColumns.MIME_TYPE}=? AND ${MediaStore.MediaColumns.VOLUME_NAME}=?" +
            if (Build.VERSION.SDK_INT >= 30) " AND ${MediaStore.MediaColumns.IS_TRASHED}=0" else ""
        val selectionArgs = arrayOf("1", packageName, target.displayName, target.relativePath, target.mimeType, target.volume)
        val updated = if (Build.VERSION.SDK_INT >= 30) {
            resolver.update(Uri.parse(uri), values, Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
                putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            })
        } else {
            @Suppress("DEPRECATION")
            val includingPending = MediaStore.setIncludePending(Uri.parse(uri))
            resolver.update(includingPending, values, selection, selectionArgs)
        }
        if (updated != 1) throw IOException("DNG pending update unconfirmed")
    }

    private fun checkRow(target: Target, uri: String, pending: Boolean) {
        val row = inspect(uri) ?: throw IOException("DNG row unavailable")
        if (row.uri != uri || !target.accepts(row, pending)) throw IOException("DNG row identity or ownership changed")
    }

    private fun query(uri: Uri, selection: String?, selectionArgs: Array<String>?): List<DngPendingRow> {
        val columns = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.VOLUME_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.OWNER_PACKAGE_NAME, MediaStore.MediaColumns.IS_PENDING,
        ) + if (Build.VERSION.SDK_INT >= 30) arrayOf(MediaStore.MediaColumns.IS_TRASHED) else emptyArray()
        val cursor = if (Build.VERSION.SDK_INT >= 30) {
            val args = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_EXCLUDE)
                if (selection != null) putString(ContentResolver.QUERY_ARG_SQL_SELECTION, selection)
                if (selectionArgs != null) putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, selectionArgs)
            }
            resolver.query(uri, columns, args, null)
        } else {
            @Suppress("DEPRECATION") // Documented API-29 compatibility; API 30+ uses query arguments.
            val includingPending = MediaStore.setIncludePending(uri)
            resolver.query(includingPending, columns, selection, selectionArgs, null)
        } ?: throw IOException("DNG row query unavailable")
        return cursor.use {
            buildList {
                while (size < 2 && it.moveToNext()) add(readRow(it)) // Two suffice to reject ambiguity.
            }
        }
    }

    private fun readRow(cursor: Cursor): DngPendingRow {
        val idIndex = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
        if (cursor.isNull(idIndex)) throw IOException("DNG row has no ID")
        fun string(column: String): String? = cursor.getString(cursor.getColumnIndexOrThrow(column))
        fun flag(column: String): Boolean? {
            val index = cursor.getColumnIndexOrThrow(column)
            if (cursor.isNull(index)) return null
            return when (cursor.getInt(index)) { 0 -> false; 1 -> true; else -> null }
        }
        return DngPendingRow(
            ContentUris.withAppendedId(collection, cursor.getLong(idIndex)).toString(),
            string(MediaStore.MediaColumns.DISPLAY_NAME), string(MediaStore.MediaColumns.VOLUME_NAME),
            string(MediaStore.MediaColumns.RELATIVE_PATH), string(MediaStore.MediaColumns.MIME_TYPE),
            string(MediaStore.MediaColumns.OWNER_PACKAGE_NAME) == packageName, flag(MediaStore.MediaColumns.IS_PENDING),
            if (Build.VERSION.SDK_INT >= 30) flag(MediaStore.MediaColumns.IS_TRASHED) else false,
        )
    }
}
