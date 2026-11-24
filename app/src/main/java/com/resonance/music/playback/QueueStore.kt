package com.resonance.music.playback

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.resonance.music.data.api.models.SongItem
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_NAME = "playback_queue"
private const val KEY_QUEUE = "queue"
private const val KEY_PLACE = "place"

/** The queue as it stood, for the account it was built against. */
data class QueueSnapshot(val account: String, val songs: List<SongItem>)

/** Where playback had got to inside the queue. */
data class QueuePlace(val index: Int, val positionMs: Long)

/**
 * Remembers the queue and the position so a system kill does not lose what the
 * user had lined up.
 *
 * The two are stored apart on purpose. The queue is large and changes only when
 * the user builds or edits it, while the position changes constantly and is
 * three numbers, so rewriting the whole queue to save a timestamp would wear the
 * disk for nothing.
 */
@Singleton
class QueueStore internal constructor(
    private val loadQueue: () -> String?,
    private val saveQueue: (String?) -> Unit,
    private val loadPlace: () -> String?,
    private val savePlace: (String?) -> Unit
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        loadQueue = { prefs(context).getString(KEY_QUEUE, null) },
        saveQueue = { value -> prefs(context).edit().putOrRemove(KEY_QUEUE, value).commit() },
        loadPlace = { prefs(context).getString(KEY_PLACE, null) },
        savePlace = { value -> prefs(context).edit().putOrRemove(KEY_PLACE, value).commit() }
    )

    private val gson = Gson()
    private var lastQueue: QueueSnapshot? = null

    /** The saved queue, or null when there is none to restore. */
    fun snapshot(): QueueSnapshot? = decodeQueueSnapshot(loadQueue()) ?: lastQueue

    fun place(): QueuePlace? = decodeQueuePlace(loadPlace())

    /** Writes the queue only when it actually changed. */
    fun saveSnapshot(snapshot: QueueSnapshot?) {
        if (snapshot == lastQueue) return
        lastQueue = snapshot
        saveQueue(snapshot?.let(::encodeQueueSnapshot))
        if (snapshot == null) savePlace(null)
    }

    fun savePlace(place: QueuePlace?) = savePlace(place?.let(::encodeQueuePlace))

    private fun encodeQueueSnapshot(snapshot: QueueSnapshot): String = gson.toJson(snapshot)

    private fun decodeQueueSnapshot(raw: String?): QueueSnapshot? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val type = object : TypeToken<QueueSnapshot>() {}.type
            gson.fromJson<QueueSnapshot>(raw, type)?.takeIf { it.songs.isNotEmpty() }
        }.getOrNull()
    }

    private fun encodeQueuePlace(place: QueuePlace): String = gson.toJson(place)

    private fun decodeQueuePlace(raw: String?): QueuePlace? {
        if (raw.isNullOrBlank()) return null
        return runCatching {
            val type = object : TypeToken<QueuePlace>() {}.type
            gson.fromJson<QueuePlace>(raw, type)?.takeIf { it.index >= 0 && it.positionMs >= 0 }
        }.getOrNull()
    }

    private companion object {
        fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}

/** Stores a value, or removes the key when the value is null. */
private fun android.content.SharedPreferences.Editor.putOrRemove(key: String, value: String?) =
    if (value == null) remove(key) else putString(key, value)
