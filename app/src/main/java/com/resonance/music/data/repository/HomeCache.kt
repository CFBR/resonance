package com.resonance.music.data.repository

import android.content.Context
import com.google.gson.Gson
import com.resonance.music.data.download.OfflineLibrary
import com.resonance.music.data.download.downloadKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private val gson = Gson()

/** The last live shelves as JSON. Empty or corrupt reads as nothing. */
internal fun encodeHomeShelves(shelves: HomeShelves): String = gson.toJson(shelves)

internal fun decodeHomeShelves(json: String?): HomeShelves? {
    if (json.isNullOrBlank()) return null
    return runCatching { gson.fromJson(json, HomeShelves::class.java) }.getOrNull()
        // A cache that stored a fallback would freeze the shorter offline view
        // over the real one; only live shelves are worth replaying.
        ?.takeIf { !it.fromSavedAlbums && it.recent.isNotEmpty() }
}

/**
 * The last live Home, kept in the app's private storage and scoped to the
 * signed-in account.
 *
 * Offline Home used to fall straight back to the saved-albums view, which
 * drops play history entirely; now it replays what the last online visit saw,
 * under the same saved-copy notice every other screen carries. A shelf entry
 * is an album id and a cover, so a full four-shelf cache is tens of kilobytes.
 */
@Singleton
class HomeCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val offline: OfflineLibrary
) {
    private fun file(): File? =
        offline.account()?.let {
            File(File(context.filesDir, "home"), "${downloadKey(it, "home", "shelves")}.json")
        }

    suspend fun get(): HomeShelves? = withContext(Dispatchers.IO) {
        val file = file()?.takeIf { it.isFile } ?: return@withContext null
        decodeHomeShelves(runCatching { file.readText() }.getOrNull())
    }

    suspend fun put(shelves: HomeShelves) = withContext(Dispatchers.IO) {
        val file = file() ?: return@withContext
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(encodeHomeShelves(shelves))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
        Unit
    }
}
