package com.resonance.music.playback

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_NAME = "favorite_queue"
private const val PREFS_KEY = "pending"

/**
 * Favourite changes the server never heard, kept as the state the user last
 * wanted rather than a list of taps: toggling a song off and on again offline
 * must send one write afterwards, not two.
 *
 * Survives process death through the store, and schedules a flush so the change
 * lands as soon as the server answers.
 */
@Singleton
class FavoriteQueue internal constructor(
    private val load: () -> String?,
    private val save: (String) -> Unit,
    private val schedule: () -> Unit = {}
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        load = { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(PREFS_KEY, null) },
        save = { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(PREFS_KEY, it).commit() },
        schedule = { scheduleQueuedWriteFlush(context) }
    )

    private val gson = Gson()
    private val type = object : TypeToken<MutableMap<String, Boolean>>() {}.type
    private var pending: MutableMap<String, Boolean> = restore()

    /** Records the state the user wants for [songId], replacing any earlier one. */
    @Synchronized
    fun record(songId: String, starred: Boolean) {
        if (pending[songId] == starred) return
        pending[songId] = starred
        save(gson.toJson(pending))
        schedule()
    }

    @Synchronized
    fun pending(): Map<String, Boolean> = pending.toMap()

    @Synchronized
    fun remove(songIds: Collection<String>) {
        if (songIds.isEmpty()) return
        pending.keys.removeAll(songIds.toSet())
        save(gson.toJson(pending))
    }

    @Synchronized
    fun isEmpty(): Boolean = pending.isEmpty()

    private fun restore(): MutableMap<String, Boolean> =
        runCatching {
            val raw = load() ?: return@runCatching mutableMapOf()
            if (raw.isBlank()) mutableMapOf()
            else gson.fromJson<MutableMap<String, Boolean>>(raw, type)?.toMutableMap() ?: mutableMapOf()
        }.getOrDefault(mutableMapOf())
}

/**
 * Sends the pending states until one fails, and reports the song ids the server
 * accepted so the caller removes exactly those. A failure means the server is
 * gone again, so the rest waits rather than burning through the queue.
 */
internal suspend fun flushFavorites(
    pending: Map<String, Boolean>,
    send: suspend (String, Boolean) -> Boolean
): List<String> {
    val delivered = mutableListOf<String>()
    for ((songId, starred) in pending) {
        if (!send(songId, starred)) return delivered
        delivered += songId
    }
    return delivered
}
