package com.resonance.music.playback

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val PREFS_NAME = "scrobble_queue"
private const val PREFS_KEY = "entries"

/** One remembered play, waiting to reach the server. */
data class ScrobbleEntry(val songId: String, val submission: Boolean, val time: Long)

/**
 * A persistent queue of scrobbles that failed to reach the server. Entries keep
 * their order and survive process death through the store. A repeat of the
 * newest entry is ignored, so one play never doubles up while it is waiting.
 */
@Singleton
class ScrobbleQueue internal constructor(
    private val load: () -> String?,
    private val save: (String) -> Unit
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        load = { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(PREFS_KEY, null) },
        save = { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(PREFS_KEY, it).commit() }
    )

    private val gson = Gson()
    private val type = object : TypeToken<List<ScrobbleEntry>>() {}.type
    private var entries: MutableList<ScrobbleEntry> = restore()

    /** True when the entry was added, false when it repeated the newest one. */
    @Synchronized
    fun add(entry: ScrobbleEntry): Boolean {
        if (entries.isNotEmpty() && entries.last() == entry) return false
        entries.add(entry)
        persist()
        return true
    }

    @Synchronized
    fun entries(): List<ScrobbleEntry> = entries.toList()

    @Synchronized
    fun remove(sent: List<ScrobbleEntry>) {
        if (sent.isEmpty()) return
        entries.removeAll(sent.toSet())
        persist()
    }

    @Synchronized
    fun isEmpty(): Boolean = entries.isEmpty()

    private fun persist() = save(gson.toJson(entries))

    private fun restore(): MutableList<ScrobbleEntry> =
        runCatching {
            val raw = this.load() ?: return@runCatching mutableListOf()
            if (raw.isBlank()) mutableListOf() else gson.fromJson<List<ScrobbleEntry>>(raw, type).toMutableList()
        }.getOrDefault(mutableListOf())
}

/**
 * Sends entries in order until one fails. Returns true when everything went
 * out; the caller removes what [send] accepted before the first failure, and
 * the rest waits for the next attempt.
 */
internal suspend fun flushScrobbles(
    entries: List<ScrobbleEntry>,
    send: suspend (ScrobbleEntry) -> Boolean
): Boolean {
    for (entry in entries) {
        if (!send(entry)) return false
    }
    return true
}
