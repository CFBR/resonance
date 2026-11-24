package com.resonance.music.data.download

import com.resonance.music.data.api.models.SongItem

/** What fetching one cover ended in. */
internal enum class ArtworkOutcome {
    /** Written to the store. */
    SAVED,

    /** The server answered and has no image for it; another pass will not help. */
    ABSENT,

    /** The attempt failed and is worth retrying later. */
    FAILED
}

/**
 * True when a status code means the server has no image for this cover. A
 * missing cover answers 404; a rate limit or timeout answers 429, 408 or 425
 * and is worth another try.
 */
internal fun imageIsAbsent(statusCode: Int?): Boolean = when (statusCode) {
    null -> false
    408, 425, 429 -> false
    in 400..499 -> true
    else -> false
}

/**
 * Counts failures in a row and says when a pass has lost the server.
 *
 * A pass runs four fetches at a time against a slow server, so the pass has to
 * give up at some point rather than grind through thousands of covers that
 * cannot arrive. A success clears the run; the covers the server simply does
 * not have are not failures, which is what used to stop a pass early and leave
 * the rest of the library waiting behind the same handful of covers.
 */
internal class FailureRun(private val limit: Int = 20) {
    private var run = 0

    @Synchronized
    fun success() {
        run = 0
    }

    @Synchronized
    fun failure(): Boolean {
        run++
        return run >= limit
    }

    /** True once the run is long enough that the server is gone, not slow. */
    @Synchronized
    fun exhausted(): Boolean = run >= limit
}

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
 * has got. Covers the server does not have are left out of the total: they can
 * never be saved, and counting them would leave the screen reporting missing
 * artwork forever. Counting is a file check per distinct cover, so callers run
 * this off the main thread.
 */
internal fun artworkSummary(
    songs: List<SongItem>,
    isCached: (String) -> Boolean,
    isAbsent: (String) -> Boolean = { false }
): ArtworkSummary {
    val covers = distinctCovers(songs).filterNot(isAbsent)
    return ArtworkSummary(saved = covers.count(isCached), total = covers.size)
}
