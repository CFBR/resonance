package com.resonance.music.data.download

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.resonance.music.data.repository.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stores offline music through the Storage Access Framework in a folder the user
 * picked, typically a removable SD card. DownloadManager cannot write to
 * removable volumes, so when a root is set the downloader writes here instead.
 *
 * All structural work (resolving directories, creating files) runs under one
 * lock: the transfer loop is sequential, and the extra cost of serializing
 * lookups is far below one provider query.
 */
@Singleton
class OfflineStorage @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsStore
) {
    private val resolver = context.contentResolver
    private val structureLock = Any()

    /** Directory path -> entry. Children load lazily, one query per directory. */
    private val directories = ConcurrentHashMap<String, DirEntry>()

    private class DirEntry(val documentId: String) {
        val children = HashMap<String, String>()
        var loaded = false
    }

    fun hasRoot(): Boolean = settings.offlineRoot.value != null

    fun rootUri(): Uri? = settings.offlineRoot.value?.let(Uri::parse)

    /** Adopts a folder picked with ACTION_OPEN_DOCUMENT_TREE as the storage root. */
    suspend fun adoptRoot(uri: Uri) = withContext(Dispatchers.IO) {
        resolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        directories.clear()
        settings.setOfflineRoot(uri.toString())
    }

    suspend fun clearRoot() = withContext(Dispatchers.IO) {
        val uri = rootUri()
        if (uri != null) runCatching {
            resolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        directories.clear()
        settings.setOfflineRoot(null)
    }

    /** Name of the picked folder as the provider shows it, for the settings row. */
    suspend fun rootDisplayName(): String? = withContext(Dispatchers.IO) {
        val root = rootUri() ?: return@withContext null
        val uri = DocumentsContract.buildDocumentUriUsingTree(root, DocumentsContract.getTreeDocumentId(root))
        queryDisplayName(uri)
    }

    /**
     * Free bytes on the volume holding the root, or null when it cannot be
     * resolved. Callers treat null as unknown and never block on it.
     */
    fun freeBytes(): Long? {
        val root = rootUri() ?: return null
        val target = runCatching { DocumentsContract.getTreeDocumentId(root) }.getOrNull()
            ?.substringBefore(':') ?: return null
        val manager = context.getSystemService(StorageManager::class.java) ?: return null
        for (dir in context.getExternalFilesDirs(null)) {
            if (dir == null) continue
            val volume = runCatching { manager.getStorageVolume(dir) }.getOrNull() ?: continue
            if ((target == "primary" && !volume.isRemovable) || volume.uuid == target) {
                return dir.usableSpace
            }
        }
        return null
    }

    fun exists(relativePath: String): Boolean = runCatching { documentUri(relativePath) }.getOrNull() != null

    /** Resolves a stored file to a content URI, or null when it is gone. */
    fun documentUri(relativePath: String): Uri? = synchronized(structureLock) {
        val root = rootUri() ?: return null
        val segments = runCatching { relativeSegments(relativePath) }.getOrNull() ?: return null
        val parent = resolveDirectory(root, segments.dropLast(1), create = false) ?: return null
        loadChildren(root, parent)
        val documentId = parent.children[segments.last()] ?: return null
        DocumentsContract.buildDocumentUriUsingTree(root, documentId)
    }

    /** Opens a stored file for reading, or null when it is gone. */
    fun openInput(relativePath: String): java.io.InputStream? {
        val uri = documentUri(relativePath) ?: return null
        return runCatching { resolver.openInputStream(uri) }.getOrNull()
    }

    fun delete(relativePath: String): Boolean = synchronized(structureLock) {
        val root = rootUri() ?: return false
        val segments = runCatching { relativeSegments(relativePath) }.getOrNull() ?: return false
        val parent = resolveDirectory(root, segments.dropLast(1), create = false) ?: return false
        loadChildren(root, parent)
        val name = segments.last()
        val documentId = parent.children[name] ?: return false
        val uri = DocumentsContract.buildDocumentUriUsingTree(root, documentId)
        val deleted = runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)
        if (deleted) parent.children.remove(name)
        deleted
    }

    /**
     * Writes a file by its relative path. Stages as "<name>.part", then renames
     * to the final name, so an interrupted transfer never looks complete. An
     * existing final file is replaced.
     *
     * The part file is created as generic bytes: providers append the canonical
     * extension of the given MIME when the name does not end with it, so a
     * typed part would become "<name>.part.mp3" and defeat the staging. The
     * rename sets the real name, and with it the real type.
     *
     * Only structural work (resolving folders, the rename) runs under the lock.
     * The byte copy runs outside it, so status lookups from the UI keep
     * answering while a large file streams. Transfers are sequential (one
     * worker), so the part file has no competing writer.
     */
    suspend fun write(relativePath: String, writer: (OutputStream) -> Unit): Unit =
        withContext(Dispatchers.IO) {
            val root = rootUri() ?: throw StorageOperationException("No offline storage folder is set")
            val segments = relativeSegments(relativePath)
            val name = segments.last()
            val parent: DirEntry
            synchronized(structureLock) {
                parent = resolveDirectory(root, segments.dropLast(1), create = true)
                    ?: throw StorageOperationException("Could not create the folder for this download")
                loadChildren(root, parent)
                deleteChild(root, parent, "$name.part")
                deleteChild(root, parent, name)
            }
            val partUri = DocumentsContract.createDocument(
                resolver,
                DocumentsContract.buildDocumentUriUsingTree(root, parent.documentId),
                "application/octet-stream",
                "$name.part"
            ) ?: throw StorageOperationException("The storage refused this download")
            try {
                if (queryDisplayName(partUri) != "$name.part") {
                    throw StorageOperationException("The storage renamed the download and it cannot be tracked")
                }
                val output = resolver.openOutputStream(partUri, "wt")
                    ?: throw StorageOperationException("Could not open the download for writing")
                output.use { stream ->
                    try {
                        writer(stream)
                        stream.flush()
                    } catch (error: java.io.IOException) {
                        // The writer mixes a network read with a disk write, so
                        // the cause is ambiguous. Failing this one song is the
                        // safe reading: when the network is genuinely down, the
                        // next file's transport errors still stop the run.
                        throw StorageOperationException(error.message ?: "The download could not be written")
                    }
                }
            } catch (error: Throwable) {
                runCatching { DocumentsContract.deleteDocument(resolver, partUri) }
                throw error
            }
            synchronized(structureLock) {
                try {
                    val finalUri = DocumentsContract.renameDocument(resolver, partUri, name)
                        ?: throw StorageOperationException("Could not finalize the download")
                    if (queryDisplayName(finalUri) != name) {
                        throw StorageOperationException("The storage renamed the download and it cannot be tracked")
                    }
                    parent.children[name] = DocumentsContract.getDocumentId(finalUri)
                } catch (error: Throwable) {
                    runCatching { DocumentsContract.deleteDocument(resolver, partUri) }
                    throw error
                }
            }
        }

    private fun deleteChild(root: Uri, parent: DirEntry, name: String) {
        val documentId = parent.children[name] ?: return
        val uri = DocumentsContract.buildDocumentUriUsingTree(root, documentId)
        if (runCatching { DocumentsContract.deleteDocument(resolver, uri) }.getOrDefault(false)) {
            parent.children.remove(name)
        }
    }

    /**
     * Walks the directory segments. Cached entries are reused; missing
     * directories are created only when asked, so read paths never write.
     */
    private fun resolveDirectory(root: Uri, segments: List<String>, create: Boolean): DirEntry? {
        val treeId = runCatching { DocumentsContract.getTreeDocumentId(root) }.getOrNull() ?: return null
        var entry = directories[""]?.takeIf { it.documentId == treeId } ?: DirEntry(treeId).also { directories[""] = it }
        var path = ""
        for (segment in segments) {
            path = if (path.isEmpty()) segment else "$path/$segment"
            val cached = directories[path]
            if (cached != null) { entry = cached; continue }
            loadChildren(root, entry)
            var childId = entry.children[segment]
            if (childId == null && create) {
                childId = createChild(root, entry, segment) ?: return null
            }
            if (childId == null) return null
            entry = DirEntry(childId).also { directories[path] = it }
        }
        return entry
    }

    private fun createChild(root: Uri, parent: DirEntry, name: String): String? {
        val uri = DocumentsContract.createDocument(
            resolver,
            DocumentsContract.buildDocumentUriUsingTree(root, parent.documentId),
            Document.MIME_TYPE_DIR,
            name
        ) ?: return null
        if (queryDisplayName(uri) != name) {
            // The provider changed the folder name. Leave no untracked directory.
            runCatching { DocumentsContract.deleteDocument(resolver, uri) }
            return null
        }
        parent.children[name] = DocumentsContract.getDocumentId(uri)
        return parent.children[name]
    }

    private fun loadChildren(root: Uri, entry: DirEntry) {
        if (entry.loaded) return
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(root, entry.documentId)
        // A failed query must not poison the cache: marking an empty listing as
        // loaded would send every later lookup down the create path, where the
        // provider answers with suffixed duplicates the exact-name check
        // rejects. Leave the entry unloaded so the next call retries.
        val loaded = runCatching {
            resolver.query(
                childrenUri,
                arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    entry.children[cursor.getString(1)] = cursor.getString(0)
                }
            }
        }.isSuccess
        if (loaded) entry.loaded = true
    }

    private fun queryDisplayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
}
