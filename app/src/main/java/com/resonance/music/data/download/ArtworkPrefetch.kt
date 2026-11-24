package com.resonance.music.data.download

import com.resonance.music.data.api.models.SongItem

/** How many of the covers the library needs are already on the device. */
data class ArtworkSummary(val saved: Int, val total: Int) {
    val missing: Int get() = total - saved
}

/** The distinct, non-blank covers across [songs], in first-seen order. */
internal fun distinctCovers(songs: List<SongItem>): List<String> {
    val seen = LinkedHashSet<String>()
    for (song in songs) {
        val cover = song.coverArt?.trim().orEmpty()
        if (cover.isNotEmpty()) seen.add(cover)
    }
    return seen.toList()
}

/** The covers an artwork prefetch pass still needs. */
internal fun missingArtworkCovers(
    songs: List<SongItem>,
    isCached: (String) -> Boolean
): List<String> = distinctCovers(songs).filterNot(isCached)

/**
 * Saved and total covers, for a screen that reports how far the offline library
 * has got. Counting is a file check per distinct cover, so callers run this off
 * the main thread.
 */
internal fun artworkSummary(
    songs: List<SongItem>,
    isCached: (String) -> Boolean
): ArtworkSummary {
    val covers = distinctCovers(songs)
    return ArtworkSummary(saved = covers.count(isCached), total = covers.size)
}
