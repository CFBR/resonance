package com.resonance.music.data.repository

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.resonance.music.data.api.models.PlaylistDetail
import com.resonance.music.data.api.models.PlaylistItem
import com.resonance.music.data.download.OfflineLibrary
import com.resonance.music.data.download.downloadKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val gson = Gson()

/** Playlists as the last online session saw them. A missing, empty, or corrupt
 *  cache reads as no playlists instead of failing the screen. */
internal fun encodePlaylists(playlists: List<PlaylistItem>): String = gson.toJson(playlists)

internal fun decodePlaylists(json: String?): List<PlaylistItem> {
    if (json.isNullOrBlank()) return emptyList()
    return runCatching {
        gson.fromJson<List<PlaylistItem>>(json, object : TypeToken<List<PlaylistItem>>() {}.type)
    }.getOrNull().orEmpty().filter { it.id.isNotEmpty() }
}

internal fun encodePlaylistDetail(detail: PlaylistDetail): String = gson.toJson(detail)

internal fun decodePlaylistDetail(json: String?): PlaylistDetail? {
    if (json.isNullOrBlank()) return null
    return runCatching { gson.fromJson(json, PlaylistDetail::class.java) }
        .getOrNull()
        ?.takeIf { it.id.isNotEmpty() }
}

/**
 * Playlist summaries and their songs, kept in the app's private storage and
 * scoped to the signed-in account. Written whenever the server answers, so a
 * playlist list and the playlists opened once stay browsable with no
 * connection. The server stays the source of truth: an offline session can
 * read playlists but cannot change them, and a playlist edited elsewhere shows
 * its cached contents until the next online visit.
 */
@Singleton
class PlaylistCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val offline: OfflineLibrary
) {
    private fun dir(): File? = offline.account()?.let { File(context.filesDir, "playlists/$it") }

    private fun indexFile(): File? = dir()?.resolve("index.json")

    private fun detailFile(id: String): File? =
        offline.account()?.let { File(context.filesDir, "playlists/$it/${downloadKey(it, "playlist", id)}.json") }

    suspend fun playlists(): List<PlaylistItem> = withContext(Dispatchers.IO) {
        decodePlaylists(read(indexFile()))
    }

    suspend fun putPlaylists(playlists: List<PlaylistItem>) = withContext(Dispatchers.IO) {
        write(indexFile(), encodePlaylists(playlists))
    }

    suspend fun detail(id: String): PlaylistDetail? = withContext(Dispatchers.IO) {
        decodePlaylistDetail(read(detailFile(id)))
    }

    suspend fun putDetail(detail: PlaylistDetail) = withContext(Dispatchers.IO) {
        write(detailFile(detail.id), encodePlaylistDetail(detail))
    }

    private fun read(file: File?): String? =
        file?.takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    private fun write(file: File?, body: String) {
        if (file == null) return
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(body)
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }
}
