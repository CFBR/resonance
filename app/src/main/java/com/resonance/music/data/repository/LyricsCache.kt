package com.resonance.music.data.repository

import android.content.Context
import com.google.gson.Gson
import com.resonance.music.data.api.models.LyricsResult
import com.resonance.music.data.api.models.StructuredLyrics
import com.resonance.music.data.download.OfflineLibrary
import com.resonance.music.data.download.downloadKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Lyrics as the screen shows them, resolved once so the cache, the screen, and
 * the download piggyback all agree on what counts as lyrics.
 */
data class CachedLyrics(
    val synced: Boolean,
    val startMs: List<Long> = emptyList(),
    val text: List<String>
)

/**
 * Synced beats unsynced beats the legacy text endpoint. A structured entry
 * without lines is not a lyrics entry, and neither is blank legacy text.
 */
internal fun resolveLyrics(structured: List<StructuredLyrics>, legacy: LyricsResult?): CachedLyrics? {
    structured.firstOrNull { it.synced == true && !it.line.isNullOrEmpty() }?.let { entry ->
        val lines = entry.line.orEmpty()
        return CachedLyrics(synced = true, startMs = lines.map { it.start ?: 0L }, text = lines.map { it.value })
    }
    structured.firstOrNull { it.synced == false && !it.line.isNullOrEmpty() }?.let { entry ->
        return CachedLyrics(synced = false, text = entry.line.orEmpty().map { it.value })
    }
    legacy?.value?.takeIf { it.isNotBlank() }?.let { text ->
        return CachedLyrics(synced = false, text = text.lines())
    }
    return null
}

/**
 * Lyrics per song in the app's private storage, scoped to the signed-in
 * account like artwork. One JSON file per song, written atomically. Fetched
 * once and kept: the lyrics screen reads the cache first, so a song whose
 * lyrics were seen once shows them with no connection at all.
 */
@Singleton
class LyricsCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val offline: OfflineLibrary
) {
    private val gson = Gson()

    private fun fileFor(songId: String): File? =
        offline.account()?.let { File(File(context.filesDir, "lyrics"), "${downloadKey(it, "lyrics", songId)}.json") }

    fun get(songId: String): CachedLyrics? {
        val file = fileFor(songId)?.takeIf { it.isFile } ?: return null
        return runCatching { gson.fromJson(file.readText(), CachedLyrics::class.java) }.getOrNull()
    }

    fun put(songId: String, lyrics: CachedLyrics) {
        val file = fileFor(songId) ?: return
        runCatching {
            file.parentFile?.mkdirs()
            val temporary = File(file.path + ".tmp")
            temporary.writeText(gson.toJson(lyrics))
            temporary.renameTo(file)
        }
    }
}
