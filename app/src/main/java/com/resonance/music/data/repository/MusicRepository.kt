package com.resonance.music.data.repository

import com.resonance.music.data.api.SubsonicApi
import com.resonance.music.data.api.SubsonicApiHelper
import com.resonance.music.data.api.models.*
import com.resonance.music.data.download.OfflineLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MusicRepository @Inject constructor(
    private val api: SubsonicApi,
    private val apiHelper: SubsonicApiHelper,
    private val offlineLibrary: OfflineLibrary,
    private val playlistCache: PlaylistCache
) {
    /**
     * Songs with a file on the storage: the offline browse source. Records that
     * are queued or failed cannot play without the server, so they are skipped.
     * Parsing happens on the library's own cache, off the calling thread.
     */
    private suspend fun savedSongs(): List<SongItem> =
        withContext(Dispatchers.IO) { offlineLibrary.browseSongs() }

    /**
     * Serves a library view from the saved songs when the network itself
     * failed. A server that answered with an error is not a connectivity
     * problem, so only IOException takes the fallback; cancellation is not an
     * IOException and passes through untouched.
     */
    private inline fun <T> offlineOnError(block: () -> T, fallback: () -> T): T =
        try {
            block()
        } catch (error: IOException) {
            fallback()
        }
    // --- Artists ---

    suspend fun getSong(id: String): SongItem? = offlineOnError(
        block = {
            val response = api.getSong(id).response
            if (!response.isOk) throw SubsonicException(response.error)
            response.song
        },
        fallback = { offlineSong(id, savedSongs()) }
    )

    suspend fun getArtists(): List<ArtistItem> = offlineOnError(
        block = {
            val root = api.getArtists()
            val env = root.response
            if (!env.isOk) throw SubsonicException(env.error)
            env.artists?.index?.flatMap { it.artist ?: emptyList() } ?: emptyList()
        },
        fallback = { offlineArtists(savedSongs()) }
    )

    suspend fun getArtistDetail(id: String): ArtistDetail? = offlineOnError(
        block = {
            val root = api.getArtist(id)
            val env = root.response
            if (!env.isOk) throw SubsonicException(env.error)
            env.artist
        },
        fallback = { offlineArtistDetail(id, savedSongs()) }
    )

    /** Artist biography + similar artists. Best-effort: older servers lack getArtistInfo2. */
    suspend fun getArtistInfo(id: String): ArtistInfo2? {
        return try {
            val root = api.getArtistInfo2(id)
            if (!root.response.isOk) null else root.response.artistInfo2
        } catch (_: Exception) {
            null
        }
    }

    // --- Albums ---

    suspend fun getAlbumList(type: String, size: Int = 20, offset: Int = 0, genre: String? = null): List<AlbumItem> =
        offlineOnError(
            block = {
                val root = api.getAlbumList(type, size, offset, genre)
                val env = root.response
                if (!env.isOk) throw SubsonicException(env.error)
                env.albumList2?.album ?: emptyList()
            },
            // Server sort types are play statistics the saved copy cannot
            // reproduce; offline lists carry one honest order, name-sorted.
            fallback = {
                offlineAlbums(savedSongs())
                    .let { if (genre == null) it else it.filter { album -> album.genre == genre } }
            }
        )

    suspend fun getAlbumsByGenre(genre: String, size: Int = 100, offset: Int = 0): List<AlbumItem> =
        getAlbumList("byGenre", size, offset, genre)

    suspend fun getGenres(): List<GenreItem> = offlineOnError(
        block = {
            val root = api.getGenres()
            val env = root.response
            if (!env.isOk) throw SubsonicException(env.error)
            env.genres?.genre?.sortedByDescending { it.albumCount ?: 0 } ?: emptyList()
        },
        fallback = { offlineGenres(savedSongs()) }
    )

    suspend fun getRecentAlbums(size: Int = 20): List<AlbumItem> = getAlbumList("recent", size)
    suspend fun getNewestAlbums(size: Int = 20): List<AlbumItem> = getAlbumList("newest", size)
    suspend fun getFrequentAlbums(size: Int = 20): List<AlbumItem> = getAlbumList("frequent", size)
    suspend fun getRandomAlbums(size: Int = 20): List<AlbumItem> = getAlbumList("random", size)

    suspend fun getAlbumDetail(id: String): AlbumDetail? = offlineOnError(
        block = {
            val root = api.getAlbum(id)
            val env = root.response
            if (!env.isOk) throw SubsonicException(env.error)
            env.album
        },
        fallback = { offlineAlbumDetail(id, savedSongs()) }
    )

    // --- Search ---

    suspend fun search(query: String): SearchResult = offlineOnError(
        block = {
            val root = api.search(query)
            val env = root.response
            if (!env.isOk) throw SubsonicException(env.error)
            env.searchResult3 ?: SearchResult()
        },
        fallback = { offlineSearch(query, savedSongs()) }
    )

    // --- Playlists ---

    suspend fun getPlaylists(): List<PlaylistItem> = offlineOnError(
        block = {
            val root = api.getPlaylists()
            val env = root.response
            if (!env.isOk) throw SubsonicException(env.error)
            (env.playlists?.playlist ?: emptyList()).also { playlistCache.putPlaylists(it) }
        },
        fallback = { playlistCache.playlists() }
    )

    suspend fun getPlaylistDetail(id: String): PlaylistDetail? = offlineOnError(
        block = {
            val root = api.getPlaylist(id)
            val env = root.response
            if (!env.isOk) throw SubsonicException(env.error)
            env.playlist?.also { playlistCache.putDetail(it) }
        },
        fallback = { playlistCache.detail(id) }
    )

    suspend fun createPlaylist(name: String, songIds: List<String>? = null) {
        val root = api.createPlaylist(name, songIds)
        if (!root.response.isOk) throw SubsonicException(root.response.error)
    }

    suspend fun deletePlaylist(id: String) {
        val root = api.deletePlaylist(id)
        if (!root.response.isOk) throw SubsonicException(root.response.error)
    }

    // --- Favorites ---

    suspend fun star(id: String) {
        val response = api.star(id = id).response
        if (!response.isOk) throw SubsonicException(response.error)
    }

    suspend fun unstar(id: String) {
        val response = api.unstar(id = id).response
        if (!response.isOk) throw SubsonicException(response.error)
    }

    suspend fun starAlbum(albumId: String) {
        api.star(albumId = albumId)
    }

    suspend fun unstarAlbum(albumId: String) {
        api.unstar(albumId = albumId)
    }

    suspend fun starArtist(artistId: String) {
        api.star(artistId = artistId)
    }

    suspend fun unstarArtist(artistId: String) {
        api.unstar(artistId = artistId)
    }

    suspend fun getStarred(): StarredResult = offlineOnError(
        block = {
            val root = api.getStarred()
            val env = root.response
            if (!env.isOk) throw SubsonicException(env.error)
            env.starred2 ?: StarredResult()
        },
        fallback = { offlineStarred(savedSongs()) }
    )

    // --- Random songs ---

    suspend fun getRandomSongs(size: Int = 20): List<SongItem> {
        val root = api.getRandomSongs(size)
        val env = root.response
        if (!env.isOk) throw SubsonicException(env.error)
        return env.randomSongs?.song ?: emptyList()
    }

    // --- Scrobble ---

    suspend fun scrobble(songId: String, submission: Boolean = true, time: Long? = null) {
        api.scrobble(songId, submission, time)
    }

    // --- Lyrics ---

    suspend fun getLyrics(artist: String?, title: String?): LyricsResult? {
        val root = api.getLyrics(artist, title)
        val env = root.response
        if (!env.isOk) return null
        return env.lyrics
    }

    suspend fun getStructuredLyrics(songId: String): List<StructuredLyrics> {
        return try {
            val root = api.getLyricsBySongId(songId)
            val env = root.response
            if (!env.isOk) return emptyList()
            env.lyricsList?.structuredLyrics ?: emptyList()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // getLyricsBySongId may not be supported on older servers
            emptyList()
        }
    }

    // --- URLs ---

    fun getStreamUrl(songId: String): String? = apiHelper.getStreamUrl(songId)
    fun getCoverArtUrl(coverArtId: String, size: Int = 300): String? =
        offlineLibrary.artworkUri(coverArtId) ?: apiHelper.getCoverArtUrl(coverArtId, size)
}

class SubsonicException(error: SubsonicError?) : Exception(
    error?.message ?: "Unknown Subsonic error (code: ${error?.code})"
)
