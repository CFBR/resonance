package com.resonance.music.ui.screens.downloads

import com.resonance.music.data.download.ArtworkSummary
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

/**
 * One line of plain facts about the covers the offline library has and lacks.
 *
 * It deliberately says nothing about a running pass. Work can sit in backoff
 * for hours against a slow server, and a line that claims to be fetching would
 * then be describing something that is not happening; the button beside it
 * already says whether a pass exists.
 */
internal fun artworkLine(summary: ArtworkSummary): String = when {
    summary.total == 0 -> "No covers needed yet."
    summary.missing == 0 -> "${summary.saved} of ${summary.total} covers saved."
    else -> "${summary.saved} of ${summary.total} covers saved, ${summary.missing} missing."
}

/**
 * Why a queued record has not moved. A record set to wait for Wi-Fi sits behind
 * an unmetered connection, so it says so when there is none; anything else is
 * waiting for a connection at all. The distinction is what the console used to
 * hide, and "N waiting" looked like a stall while the tablet sat on a hotspot.
 */
internal fun queuedReason(track: OfflineTrack, unmeteredAvailable: Boolean): String = when {
    track.record.wifiOnly && !unmeteredAvailable -> "Queued; waiting for Wi-Fi"
    else -> "Queued; waiting for a connection"
}
