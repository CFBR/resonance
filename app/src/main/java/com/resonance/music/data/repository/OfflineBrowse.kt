package com.resonance.music.data.repository

import com.resonance.music.data.api.models.AlbumDetail
import com.resonance.music.data.api.models.AlbumItem
import com.resonance.music.data.api.models.ArtistDetail
import com.resonance.music.data.api.models.ArtistItem
import com.resonance.music.data.api.models.GenreItem
import com.resonance.music.data.api.models.SearchResult
import com.resonance.music.data.api.models.StarredResult
import com.resonance.music.data.api.models.SongItem

/**
 * Derives the library views from saved songs when the server cannot be
 * reached. Pure mapping: every function takes the saved songs and returns the
 * same shapes the server-backed screens consume, so the fallback needs no UI
 * changes and no new state.
 *
 * Honest limits, by design: playlists cannot be derived offline, favorite
 * state is whatever was true when the song was saved, and sorting follows the
 * saved copy rather than the server's sort type.
 */

/** Groups saved songs into the artist list, sorted by name. */
internal fun offlineArtists(songs: List<SongItem>): List<ArtistItem> =
    songs.groupBy { it.artistId ?: it.artist ?: "" }
        .map { (key, group) ->
            val first = group.first()
            ArtistItem(
                id = key.ifEmpty { first.artist ?: "" },
                name = first.artist ?: first.title,
                coverArt = first.coverArt,
                albumCount = group.mapNotNull { it.albumId }.distinct().size
            )
        }
        .sortedBy { it.name.lowercase() }

/** Groups saved songs into the album list, sorted by name. */
internal fun offlineAlbums(songs: List<SongItem>): List<AlbumItem> =
    offlineAlbumGroups(songs).map { (id, group) ->
        val first = group.first()
        AlbumItem(
            id = id,
            name = first.album ?: "",
            artist = first.artist,
            artistId = first.artistId,
            coverArt = first.coverArt,
            songCount = group.size,
            duration = group.sumOf { it.duration ?: 0 },
            year = first.year,
            genre = first.genre
        )
    }.sortedBy { it.name.lowercase() }

/** The songs of one saved album in disc, then track order, or null. */
internal fun offlineAlbumDetail(albumId: String, songs: List<SongItem>): AlbumDetail? {
    val group = offlineAlbumGroups(songs)[albumId] ?: return null
    val first = group.first()
    return AlbumDetail(
        id = albumId,
        name = first.album ?: "",
        artist = first.artist,
        artistId = first.artistId,
        coverArt = first.coverArt,
        songCount = group.size,
        duration = group.sumOf { it.duration ?: 0 },
        year = first.year,
        genre = first.genre,
        song = group.sortedWith(
            compareBy({ it.discNumber ?: 0 }, { it.track ?: Int.MAX_VALUE })
        )
    )
}

/** The albums of one saved artist, or null when nothing matches. */
internal fun offlineArtistDetail(artistId: String, songs: List<SongItem>): ArtistDetail? {
    val group = songs.filter { (it.artistId ?: it.artist) == artistId || it.artist == artistId }
    if (group.isEmpty()) return null
    val first = group.first()
    return ArtistDetail(
        id = artistId,
        name = first.artist ?: "",
        coverArt = first.coverArt,
        albumCount = group.mapNotNull { it.albumId }.distinct().size,
        album = offlineAlbums(group)
    )
}

/** Distinct genres over saved songs, ignoring songs without one. */
internal fun offlineGenres(songs: List<SongItem>): List<GenreItem> =
    songs.filterNot { it.genre.isNullOrBlank() }
        .groupBy { it.genre!! }
        .map { (genre, group) ->
            GenreItem(
                name = genre,
                songCount = group.size,
                albumCount = group.mapNotNull { it.albumId }.distinct().size
            )
        }
        .sortedByDescending { it.songCount ?: 0 }

/** The saved songs that were favorites when they were saved. */
internal fun offlineStarred(songs: List<SongItem>): StarredResult =
    StarredResult(song = songs.filter { it.starred != null })

/** Title, artist, and album matching, case-insensitive on a trimmed query. */
internal fun offlineSearch(query: String, songs: List<SongItem>): SearchResult {
    val needle = query.trim().lowercase()
    if (needle.isEmpty()) return SearchResult()
    val matches: (String?) -> Boolean = { it?.lowercase()?.contains(needle) == true }
    val songHits = songs.filter { matches(it.title) || matches(it.artist) || matches(it.album) }
    return SearchResult(
        artist = offlineArtists(songHits).filter { matches(it.name) },
        album = offlineAlbums(songHits).filter { matches(it.name) },
        song = songHits
    )
}

/** One saved song by id, for detail screens that load a single song. */
internal fun offlineSong(id: String, songs: List<SongItem>): SongItem? =
    songs.firstOrNull { it.id == id }

private fun offlineAlbumGroups(songs: List<SongItem>): Map<String, List<SongItem>> =
    songs.filter { it.albumId != null }.groupBy { it.albumId!! }
