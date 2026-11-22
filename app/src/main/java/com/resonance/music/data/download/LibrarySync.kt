package com.resonance.music.data.download

import com.resonance.music.data.api.models.AlbumItem
import com.resonance.music.data.api.models.SongItem

/**
 * How many songs this device knows about, by offline state. "Known" means a
 * record exists; the library total on the server can be larger until an
 * enumeration run has walked every album.
 */
class OfflineTotals(val ready: Int, val queued: Int, val failed: Int) {
    val known: Int get() = ready + queued + failed
}

fun offlineTotals(tracks: List<OfflineTrack>): OfflineTotals {
    var ready = 0
    var queued = 0
    var failed = 0
    for (track in tracks) {
        when (track.status) {
            OfflineStatus.READY -> ready++
            OfflineStatus.QUEUED, OfflineStatus.DOWNLOADING -> queued++
            OfflineStatus.FAILED -> failed++
        }
    }
    return OfflineTotals(ready, queued, failed)
}

/**
 * Walks the server's album list page by page and queues each page's songs in
 * one batch, so one queue call per page keeps the transfer chain short.
 * Returns the number of songs newly queued.
 *
 * An album whose songs fail to load is skipped; the walk continues. When
 * [keepGoing] reports the destination is full, the walk stops early and the
 * already-queued songs keep their state, ready to resume.
 */
internal suspend fun enumerateLibrary(
    listAlbums: suspend (offset: Int) -> List<AlbumItem>,
    albumSongs: suspend (albumId: String) -> List<SongItem>,
    queueSongs: suspend (songs: List<SongItem>) -> Int,
    keepGoing: suspend () -> Boolean,
    onProgress: suspend (albumsScanned: Int, songsQueued: Int) -> Unit = { _, _ -> },
    pageSize: Int = 200
): Int {
    var scanned = 0
    var queuedTotal = 0
    var offset = 0
    while (true) {
        val page = listAlbums(offset)
        if (page.isEmpty()) return queuedTotal
        val songs = mutableListOf<SongItem>()
        for (album in page) {
            if (!keepGoing()) {
                onProgress(scanned, queuedTotal)
                return queuedTotal
            }
            val loaded = try {
                albumSongs(album.id)
            } catch (_: Exception) {
                continue
            }
            songs += loaded
            scanned++
            // Per album, not per page: a page can take an hour on a slow
            // server, and the notification should not sit silent through it.
            onProgress(scanned, queuedTotal)
        }
        queuedTotal += queueSongs(songs)
        onProgress(scanned, queuedTotal)
        if (page.size < pageSize) return queuedTotal
        offset += pageSize
    }
}
