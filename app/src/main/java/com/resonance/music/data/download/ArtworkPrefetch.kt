package com.resonance.music.data.download

import com.resonance.music.data.api.models.SongItem

/**
 * The covers an artwork prefetch pass still needs: distinct, non-blank, and
 * not already in the artwork store.
 */
internal fun missingArtworkCovers(
    songs: List<SongItem>,
    isCached: (String) -> Boolean
): List<String> {
    val seen = LinkedHashSet<String>()
    for (song in songs) {
        val cover = song.coverArt?.trim().orEmpty()
        if (cover.isNotEmpty()) seen.add(cover)
    }
    return seen.filterNot(isCached)
}
