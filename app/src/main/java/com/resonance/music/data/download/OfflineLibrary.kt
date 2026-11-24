package com.resonance.music.data.download

import android.app.DownloadManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import androidx.annotation.Keep
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import com.resonance.music.data.api.models.SongItem
import com.resonance.music.data.repository.AuthRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.channels.Channel
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Keep
data class OfflineRecord(
    val key: String,
    val account: String,
    val song: SongItem,
    val downloadId: Long = -1,
    val wifiOnly: Boolean = false,
    val error: String? = null,
    // Set when the file was written through the Storage Access Framework instead
    // of DownloadManager. Keyed by downloadPath(song, key), rooted at the folder
    // the user picked. Null means DownloadManager owns the file.
    val relativePath: String? = null
)

enum class OfflineStatus { QUEUED, DOWNLOADING, READY, FAILED }

data class OfflineTrack(
    val record: OfflineRecord,
    val status: OfflineStatus,
    val bytes: Long = 0,
    val total: Long = -1,
    val uri: Uri? = null
)

@Singleton
/** Song id to ready track, first occurrence winning: the order [OfflineLibrary.localUri]
 *  matched when it scanned linearly. */
internal fun buildReadyIndex(tracks: List<OfflineTrack>): Map<String, OfflineTrack> {
    val index = HashMap<String, OfflineTrack>(tracks.size)
    for (track in tracks) {
        if (track.status == OfflineStatus.READY && !index.containsKey(track.record.song.id)) {
            index[track.record.song.id] = track
        }
    }
    return index
}

/** Two seconds while DownloadManager reports byte progress, otherwise a slow
 *  wakeup: record state changes arrive on the changes channel, not the poll.
 *
 * [quietPolls] counts consecutive polls that changed nothing. A library at
 * rest still gets checked, but the pause between checks doubles up to a cap,
 * because a poll that found nothing last time is unlikely to find something
 * now: re-parsing a 9,000-record library every minute spends tens of
 * megabytes of heap a minute for nothing, and on a heap that also carries a
 * restored playback queue that churn reaches the ceiling and the app dies
 * with OutOfMemoryError. Any event on the changes channel resets the count.
 */
internal fun pollDelayMs(tracks: List<OfflineTrack>, quietPolls: Int = 0): Long {
    if (tracks.any { it.status == OfflineStatus.DOWNLOADING }) return 2_000L
    var delay = 60_000L
    repeat(quietPolls.coerceIn(0, 10)) { delay *= 2 }
    return delay.coerceAtMost(QUIET_POLL_CAP_MS)
}

/** The longest a quiet library waits between checks. */
internal const val QUIET_POLL_CAP_MS = 15 * 60_000L

/**
 * The parsed records, reused until something writes to the store.
 *
 * A write is the only way the records can change: every path that mutates
 * one goes through this SharedPreferences, and the listener turns any write
 * into a new revision. Re-parsing is nine thousand Gson deserialisations,
 * so serving a poll from the cache where nothing changed keeps the steady
 * state cheap; a miss falls through to [parse] exactly as before.
 */
internal class RecordsCache(
    private val parse: (String) -> List<OfflineRecord>
) {
    private var cached: Triple<Long, String, List<OfflineRecord>>? = null

    @Synchronized
    fun records(revision: Long, account: String): List<OfflineRecord> {
        val hit = cached
        if (hit != null && hit.first == revision && hit.second == account) return hit.third
        return parse(account).also { cached = Triple(revision, account, it) }
    }
}

class OfflineLibrary @Inject constructor(
    @ApplicationContext private val context: Context,
    private val auth: AuthRepository,
    private val storage: OfflineStorage
) {
    private val preferences = context.getSharedPreferences("offline_library", Context.MODE_PRIVATE)
    /** Bumped by any write to the store, so the cache knows when to re-parse. */
    private val revision = java.util.concurrent.atomic.AtomicLong(0)
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val _tracks = MutableStateFlow<List<OfflineTrack>>(emptyList())
    val tracks: StateFlow<List<OfflineTrack>> = _tracks.asStateFlow()
    /** Song id to ready track, rebuilt whenever [_tracks] is. Local lookups
     *  are O(1); a linear scan per song made building a large queue O(n²). */
    @Volatile private var readyIndex: Map<String, OfflineTrack> = emptyMap()
    private val _syncFavorites = MutableStateFlow(false)
    val syncFavorites: StateFlow<Boolean> = _syncFavorites.asStateFlow()

    private val recordCache = RecordsCache { account -> parseRecords(account) }

    init {
        preferences.registerOnSharedPreferenceChangeListener { _, _ -> revision.incrementAndGet() }
        ContextCompat.registerReceiver(context, object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { changes.trySend(Unit) }
        }, IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE), ContextCompat.RECEIVER_EXPORTED)
        scope.launch {
            auth.generation.collectLatest { generation ->
                _tracks.value = emptyList()
                readyIndex = emptyMap()
                _syncFavorites.value = false
                if (generation % 2L != 0L) return@collectLatest
                var quiet = 0
                while (isActive) {
                    val before = _tracks.value
                    refresh()
                    val changed = before !== _tracks.value && before != _tracks.value
                    if (changed) quiet = 0 else quiet++
                    // A record only moves on its own through DownloadManager byte
                    // progress; every other state change (save, retry, completion
                    // broadcast) arrives on the changes channel, which resets the
                    // quiet count, and a library at rest doubles its pauses.
                    val woke = withTimeoutOrNull(pollDelayMs(_tracks.value, quiet)) { changes.receive() }
                    if (woke != null) quiet = 0
                    delay(500)
                }
            }
        }
    }

    fun account(): String? = auth.getCachedCredentials()?.let {
        downloadKey(it.serverUrl.trimEnd('/'), it.username, "account")
    }

    fun records(account: String): List<OfflineRecord> =
        recordCache.records(revision.get(), account)

    private fun parseRecords(account: String): List<OfflineRecord> = synchronized(this) {
        preferences.all.entries
            .filter { it.key.startsWith("track_") }
            .mapNotNull { runCatching { gson.fromJson(it.value as String, OfflineRecord::class.java) }.getOrNull() }
            .filter { it.account == account }
    }

    @Synchronized
    fun save(record: OfflineRecord) {
        check(preferences.edit().putString("track_${record.key}", gson.toJson(record)).commit())
        browseCache = null
        changes.trySend(Unit)
    }

    @Synchronized
    fun saveAll(records: List<OfflineRecord>) {
        val editor = preferences.edit()
        records.forEach { editor.putString("track_${it.key}", gson.toJson(it)) }
        check(editor.commit())
        browseCache = null
        changes.trySend(Unit)
    }

    @Synchronized
    fun replace(expected: OfflineRecord, updated: OfflineRecord): Boolean {
        if (record(expected.key) != expected) return false
        save(updated)
        return true
    }

    @Synchronized
    fun record(key: String): OfflineRecord? = preferences.getString("track_$key", null)?.let {
        runCatching { gson.fromJson(it, OfflineRecord::class.java) }.getOrNull()
    }

    /**
     * Songs with a file on the storage, for the offline browse fallback: the
     * saved record's metadata, without the per-file storage check that
     * [refresh] pays for. Presence is trusted from the record, so a file
     * deleted behind the app's back still appears here; playback then falls
     * back to streaming, and the Downloads screen shows the truth.
     *
     * Parsed once and cached until a record changes, so offline search does
     * not re-parse the library on every keystroke.
     */
    fun browseSongs(): List<SongItem> {
        val account = account() ?: return emptyList()
        browseCache?.let { (cachedAccount, songs) -> if (cachedAccount == account) return songs }
        val songs = records(account).filter { it.relativePath != null }.map { it.song }
        browseCache = account to songs
        return songs
    }

    @Volatile private var browseCache: Pair<String, List<SongItem>>? = null

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val generation = auth.generation.value
        if (generation % 2L != 0L) return@withContext
        val account = account()
        val result = account?.let { records(it).sortedWith(compareBy(
            { record -> record.song.artist.orEmpty().lowercase() },
            { record -> record.song.album.orEmpty().lowercase() },
            { record -> record.song.discNumber ?: 0 },
            { record -> record.song.track ?: 0 },
            { record -> record.song.title.lowercase() },
            { record -> record.song.id }
        )).map(::inspect) }.orEmpty()
        if (generation == auth.generation.value && account == account()) {
            _tracks.value = result
            readyIndex = buildReadyIndex(result)
            _syncFavorites.value = account != null && favoritesEnabled(account)
        }
    }

    fun inspect(record: OfflineRecord): OfflineTrack {
        if (record.error != null) return OfflineTrack(record, OfflineStatus.FAILED)
        if (record.relativePath != null) {
            val uri = storage.documentUri(record.relativePath)
            return if (uri != null) OfflineTrack(record, OfflineStatus.READY, uri = uri)
            else OfflineTrack(record.copy(error = "The saved file is missing. Tap retry."), OfflineStatus.FAILED)
        }
        if (record.downloadId < 0) return OfflineTrack(record, OfflineStatus.QUEUED)
        manager.query(DownloadManager.Query().setFilterById(record.downloadId))?.use { cursor ->
            if (cursor.moveToFirst()) {
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val bytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                if (status == DownloadManager.STATUS_SUCCESSFUL) {
                    val uri = manager.getUriForDownloadedFile(record.downloadId)
                    val exists = uri != null && runCatching {
                        context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize != 0L } == true
                    }.getOrDefault(false)
                    if (exists) return OfflineTrack(record, OfflineStatus.READY, bytes, total, uri)
                } else if (status != DownloadManager.STATUS_FAILED) {
                    return OfflineTrack(record, OfflineStatus.DOWNLOADING, bytes, total)
                }
            }
        }
        return OfflineTrack(record.copy(error = "Download failed or file was removed. Tap retry."), OfflineStatus.FAILED)
    }

    fun localUri(songId: String): Uri? {
        val track = readyIndex[songId] ?: return null
        return if (track.record.account == account()) track.uri else null
    }

    fun artworkFile(coverArt: String, account: String? = account()): File? = account?.let {
        File(context.filesDir, "offline_art/${downloadKey(it, "art", coverArt)}.jpg")
    }

    fun artworkUri(coverArt: String): String? = artworkFile(coverArt)?.takeIf { it.isFile }?.let { Uri.fromFile(it).toString() }

    /**
     * Covers the server answered without an image for. Kept so a later pass does
     * not spend its first attempts on the same covers and stop again, and so the
     * status line stops counting artwork that will never arrive.
     */
    fun absentArtwork(): Set<String> =
        preferences.getStringSet(KEY_ARTWORK_ABSENT, emptySet()).orEmpty().toSet()

    fun markArtworkAbsent(coverArt: String) {
        synchronized(this) {
            preferences.edit().putStringSet(KEY_ARTWORK_ABSENT, absentArtwork() + coverArt).commit()
        }
    }

    suspend fun remove(track: OfflineTrack) = withContext(Dispatchers.IO) {
        synchronized(this@OfflineLibrary) {
            if (track.record.account != account()) return@withContext
            val current = record(track.record.key) ?: return@withContext
            preferences.edit().remove("track_${track.record.key}").commit()
            browseCache = null
            if (current.relativePath != null) storage.delete(current.relativePath)
            else if (current.downloadId >= 0) manager.remove(current.downloadId)
        }
        refresh()
    }

    @Synchronized
    fun updateSong(key: String, song: SongItem) {
        record(key)?.let { save(it.copy(song = song)) }
    }

    fun favoritesEnabled(account: String): Boolean = preferences.getBoolean("favorites_$account", false)

    fun setFavoritesEnabled(enabled: Boolean) {
        val account = account() ?: return
        preferences.edit().putBoolean("favorites_$account", enabled).apply()
        _syncFavorites.value = enabled
    }

    private companion object {
        const val KEY_ARTWORK_ABSENT = "artwork_absent"
    }
}
