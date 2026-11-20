package com.resonance.music

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import java.io.File
import android.util.Log
import java.io.IOException

/**
 * A minimal documents provider so OfflineStorageTest can exercise the real
 * DocumentsContract call paths (create, rename, delete, children queries,
 * stream modes) without a user grant, which Android only hands out through
 * the picker. Mirrors ExternalStorageProvider semantics: exact display names,
 * no mangling. The real Samsung provider is covered by the manual picker
 * acceptance pass.
 *
 * Document ids are relative paths from the root, percent-encoded as one URI
 * segment, exactly like ExternalStorageProvider ids. Backing files live under
 * a cache directory.
 */
class FakeDocumentsProvider : ContentProvider() {

    private lateinit var root: File
    private val authority get() = "com.resonance.music.testdocuments"

    override fun onCreate(): Boolean {
        root = (context ?: return false).getDir("fake_documents", Context.MODE_PRIVATE)
        return true
    }

    private fun fileFor(documentId: String): File =
        if (documentId == ROOT_ID) root else File(root, documentId)

    private fun fileForUri(uri: Uri): File =
        fileFor(DocumentsContract.getDocumentId(uri))

    private object CallMethods {
        const val CREATE_DOCUMENT = "android:createDocument"
        const val RENAME_DOCUMENT = "android:renameDocument"
        const val DELETE_DOCUMENT = "android:deleteDocument"
        const val EXTRA_URI = "uri"
        const val EXTRA_RESULT = "result"
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val out = Bundle()
        when (method) {
            CallMethods.CREATE_DOCUMENT -> {
                val parent = fileForUri(extras!!.getParcelable<Uri>(CallMethods.EXTRA_URI)!!)
                val mime = extras.getString(Document.COLUMN_MIME_TYPE)
                val name = extras.getString(Document.COLUMN_DISPLAY_NAME)!!
                val created = File(parent, name)
                if (mime == Document.MIME_TYPE_DIR) {
                    if (!created.isDirectory && !created.mkdirs()) throw IOException("exists")
                } else {
                    created.parentFile?.mkdirs()
                    if (!created.createNewFile()) throw IOException("exists")
                }
                out.putParcelable(
                    CallMethods.EXTRA_URI,
                    DocumentsContract.buildDocumentUri(authority, relative(created))
                )
            }
            CallMethods.RENAME_DOCUMENT -> {
                val file = fileForUri(extras!!.getParcelable<Uri>(CallMethods.EXTRA_URI)!!)
                val name = extras.getString(Document.COLUMN_DISPLAY_NAME)!!
                val target = File(file.parentFile, name)
                if (!file.renameTo(target)) throw IOException("rename failed")
                out.putParcelable(
                    CallMethods.EXTRA_URI,
                    DocumentsContract.buildDocumentUri(authority, relative(target))
                )
            }
            CallMethods.DELETE_DOCUMENT -> {
                val file = fileForUri(extras!!.getParcelable<Uri>(CallMethods.EXTRA_URI)!!)
                out.putBoolean(CallMethods.EXTRA_RESULT, file.deleteRecursively())
            }
            else -> return null
        }
        return out
    }

    /** Honors the projection, like a real provider: callers may ask for one column. */
    private fun rowOf(cursor: MatrixCursor, documentId: String, name: String) {
        cursor.addRow(cursor.columnNames.map { column ->
            when (column) {
                Document.COLUMN_DOCUMENT_ID -> documentId
                Document.COLUMN_DISPLAY_NAME -> name
                else -> throw IllegalArgumentException("column $column")
            }
        })
    }

    private fun listChildren(cursor: MatrixCursor, documentId: String) {
        fileFor(documentId).takeIf { it.isDirectory }?.listFiles()
            ?.sortedBy { it.name }?.forEach { rowOf(cursor, relative(it), it.name) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? {
        val segments = uri.pathSegments ?: return null
        val columns = projection ?: arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME)
        val cursor = MatrixCursor(columns)
        when (segments.firstOrNull()) {
            "document" -> rowOf(cursor, segments[1], fileFor(segments[1]).name)
            "tree" -> when {
                // Android 16: children of <parent> appear after the document segment.
                segments.size == 5 && segments[4] == "children" ->
                    listChildren(cursor, segments[3])
                // Earlier platforms: children/<parent>.
                segments.size == 4 && segments[2] == "children" ->
                    listChildren(cursor, segments[3])
                segments.size == 4 && segments[2] == "document" ->
                    rowOf(cursor, segments[3], fileFor(segments[3]).name ?: segments[1])
                else -> return null
            }
            else -> return null
        }
        return cursor
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val file = fileForUri(uri)
        return when (mode) {
            "r" -> ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            else -> {
                file.parentFile?.mkdirs()
                val flags = ParcelFileDescriptor.MODE_WRITE_ONLY or
                    (if ('t' in mode) ParcelFileDescriptor.MODE_TRUNCATE else 0) or
                    (if ('a' in mode) ParcelFileDescriptor.MODE_APPEND else 0)
                ParcelFileDescriptor.open(file, flags)
            }
        }
    }

    private fun relative(file: File): String =
        if (file == root) ROOT_ID else file.relativeToOrSelf(root).path.removePrefix("/")

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val ROOT_ID = "root"
        val AUTHORITY = "com.resonance.music.testdocuments"
    }
}
