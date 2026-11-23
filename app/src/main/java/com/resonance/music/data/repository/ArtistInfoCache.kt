package com.resonance.music.data.repository

import android.content.Context
import com.google.gson.Gson
import com.resonance.music.data.api.models.ArtistInfo2
import com.resonance.music.data.download.OfflineLibrary
import com.resonance.music.data.download.downloadKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val gson = Gson()

/** An artist's introduction as the last online session saw it. Empty or
 *  corrupt reads as nothing, so a bad file cannot break the artist screen. */
internal fun encodeArtistInfo(info: ArtistInfo2): String = gson.toJson(info)

internal fun decodeArtistInfo(json: String?): ArtistInfo2? {
    if (json.isNullOrBlank()) return null
    val info = runCatching { gson.fromJson(json, ArtistInfo2::class.java) }.getOrNull() ?: return null
    val hasText = !info.biography.isNullOrBlank() || !info.similarArtist.isNullOrEmpty()
    return if (hasText) info else null
}

/**
 * Artist biographies and similar artists, kept in the app's private storage
 * and scoped to the signed-in account. The artist screen fetches this
 * separately from the artist's albums, and offline it used to have nothing to
 * show; now it shows what the last online visit fetched.
 */
@Singleton
class ArtistInfoCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val offline: OfflineLibrary
) {
    private fun fileFor(artistId: String): File? =
        offline.account()?.let {
            File(File(context.filesDir, "artist_info"), "${downloadKey(it, "artist_info", artistId)}.json")
        }

    suspend fun get(artistId: String): ArtistInfo2? = withContext(Dispatchers.IO) {
        val file = fileFor(artistId)?.takeIf { it.isFile } ?: return@withContext null
        decodeArtistInfo(runCatching { file.readText() }.getOrNull())
    }

    suspend fun put(artistId: String, info: ArtistInfo2) = withContext(Dispatchers.IO) {
        val file = fileFor(artistId) ?: return@withContext
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(encodeArtistInfo(info))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
        Unit
    }
}
