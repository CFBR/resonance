package com.resonance.music.playback

/**
 * A song list mapped to the items that can play, with the matching songs and
 * where playback should start.
 */
internal data class BuiltQueue<S, T>(
    val items: List<T>,
    val songs: List<S>,
    val startIndex: Int
)

/**
 * Drops the songs [build] cannot turn into an item and reports where playback
 * should start in what is left.
 *
 * A song is dropped when it has nowhere to play from: no saved copy while the
 * server is unreachable, or no stream URL at all. Dropping shifts every later
 * position, so the start is counted in kept songs rather than copied from the
 * original index. That keeps the start on the song the user tapped, or on the
 * next song that can play when that one went missing.
 */
internal fun <S, T> builtQueue(songs: List<S>, startIndex: Int, build: (S) -> T?): BuiltQueue<S, T> {
    val items = ArrayList<T>(songs.size)
    val kept = ArrayList<S>(songs.size)
    var start = 0
    songs.forEachIndexed { index, song ->
        val item = build(song) ?: return@forEachIndexed
        if (index < startIndex) start++
        items += item
        kept += song
    }
    return BuiltQueue(items, kept, start.coerceIn(0, (items.size - 1).coerceAtLeast(0)))
}
