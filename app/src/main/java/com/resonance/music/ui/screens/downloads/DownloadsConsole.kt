package com.resonance.music.ui.screens.downloads

import com.resonance.music.data.download.OfflineStatus
import com.resonance.music.data.download.OfflineTrack

/** Status buckets the console offers; Waiting is queue plus active transfer. */
enum class DownloadFilter {
    ALL, SAVED, WAITING, FAILED;

    fun matches(status: OfflineStatus): Boolean = when (this) {
        ALL -> true
        SAVED -> status == OfflineStatus.READY
        WAITING -> status == OfflineStatus.QUEUED || status == OfflineStatus.DOWNLOADING
        FAILED -> status == OfflineStatus.FAILED
    }
}

/** One album's worth of download rows, in display order. */
data class DownloadGroup(
    val album: String,
    val artist: String?,
    val coverArt: String?,
    val songs: List<OfflineTrack>
)

/** Groups tracks by album for the management console: filtered by the search
 *  text (title, artist, or album) and the status bucket, each album's songs
 *  in track order, albums sorted by artist then name. Songs without an album
 *  id each stand alone under their own title. */
internal fun consoleGroups(
    tracks: List<OfflineTrack>,
    query: String,
    filter: DownloadFilter
): List<DownloadGroup> {
    val needle = query.trim().lowercase()
    val matching = tracks.filter { track ->
        filter.matches(track.status) && (needle.isEmpty() || listOfNotNull(
            track.record.song.title, track.record.song.artist, track.record.song.album
        ).any { it.lowercase().contains(needle) })
    }
    return matching
        .groupBy { it.record.song.albumId ?: "song:${it.record.song.id}" }
        .map { (_, groupTracks) ->
            val first = groupTracks.first()
            DownloadGroup(
                album = first.record.song.album ?: first.record.song.title,
                artist = groupTracks.firstNotNullOfOrNull { it.record.song.artist },
                coverArt = groupTracks.firstNotNullOfOrNull { it.record.song.coverArt },
                songs = groupTracks.sortedWith(
                    compareBy({ it.record.song.track ?: Int.MAX_VALUE }, { it.record.song.title })
                )
            )
        }
        .sortedWith(compareBy({ it.artist?.lowercase() ?: "" }, { it.album.lowercase() }))
}
