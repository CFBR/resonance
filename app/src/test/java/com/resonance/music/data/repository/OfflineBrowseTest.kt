package com.resonance.music.data.repository

import com.resonance.music.data.api.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The offline browse fallback derives the library views from saved songs, so
 * the mappings must group and count exactly the way the server-backed screens
 * group and count.
 */
class OfflineBrowseTest {

    private fun song(
        id: String,
        title: String,
        artist: String? = "Artist A",
        artistId: String? = "ar1",
        album: String? = "Album One",
        albumId: String? = "al1",
        disc: Int? = null,
        track: Int? = null,
        genre: String? = "Rock",
        duration: Int? = 100,
        year: Int? = 2020,
        starred: String? = null
    ) = SongItem(
        id = id, title = title, album = album, albumId = albumId,
        artist = artist, artistId = artistId, track = track,
        year = year, genre = genre, coverArt = "c$id",
        duration = duration, discNumber = disc, starred = starred
    )

    private val saved = listOf(
        song("s1", "Alpha", track = 1, starred = "2024-01-01"),
        song("s2", "Beta Song", track = 2, duration = 50),
        song("s3", "Gamma", artist = "Artist B", artistId = "ar2",
            album = "Album Two", albumId = "al2", track = 1),
        song("s4", "Delta", artist = "Artist B", artistId = "ar2",
            album = "Album Three", albumId = "al3", track = 1, genre = null),
        song("s5", "album two in title", artist = "Artist C", artistId = "ar3",
            album = "album TWO", albumId = "al4", track = 1, genre = null)
    )

    @Test
    fun `artists group by id with album counts and sort by name`() {
        val artists = offlineArtists(saved)
        assertEquals(listOf("Artist A", "Artist B", "Artist C"), artists.map { it.name })
        assertEquals(listOf("ar1", "ar2", "ar3"), artists.map { it.id })
        assertEquals(1, artists[0].albumCount)
        assertEquals(2, artists[1].albumCount)
    }

    @Test
    fun `artists without an id group by name`() {
        val loose = listOf(song("x", "T", artist = "Unknown", artistId = null))
        val artists = offlineArtists(loose)
        assertEquals(1, artists.size)
        assertEquals("Unknown", artists[0].id)
    }

    @Test
    fun `albums group by id with song counts and durations`() {
        val albums = offlineAlbums(saved)
        val one = albums.first { it.id == "al1" }
        assertEquals("Album One", one.name)
        assertEquals("Artist A", one.artist)
        assertEquals(2, one.songCount)
        assertEquals(150, one.duration)
        assertEquals(2020, one.year)
        assertEquals("Rock", one.genre)
        assertEquals("cs1", one.coverArt)
    }

    @Test
    fun `album detail orders songs by disc then track`() {
        val mixed = listOf(
            song("s9", "Third", disc = 1, track = 2),
            song("s8", "First", disc = 1, track = 1),
            song("s7", "Second", disc = 1, track = null)
        )
        val detail = offlineAlbumDetail("al1", mixed)
        assertNotNull(detail)
        assertEquals(listOf("s8", "s9", "s7"), detail!!.song.orEmpty().map { it.id })
        assertEquals(3, detail.songCount)
    }

    @Test
    fun `album detail is null for an unsaved album`() {
        assertNull(offlineAlbumDetail("nope", saved))
    }

    @Test
    fun `artist detail lists that artist's albums`() {
        val detail = offlineArtistDetail("ar2", saved)
        assertNotNull(detail)
        assertEquals("Artist B", detail!!.name)
        assertEquals(listOf("Album Three", "Album Two"), detail.album.orEmpty().map { it.name })
    }

    @Test
    fun `genres count songs and distinct albums`() {
        val genres = offlineGenres(saved)
        val rock = genres.first { it.name == "Rock" }
        assertEquals(3, rock.songCount)
        assertEquals(2, rock.albumCount)
        assertTrue(genres.none { it.name == "" })
    }

    @Test
    fun `starred keeps only favorited songs in server order`() {
        val starred = offlineStarred(saved)
        assertEquals(listOf("s1"), starred.song.orEmpty().map { it.id })
        assertTrue(starred.artist.isNullOrEmpty())
    }

    @Test
    fun `search matches title, artist, and album case-insensitively`() {
        val result = offlineSearch("album two", saved)
        assertEquals(setOf("al2", "al4"), result.album.orEmpty().map { it.id }.toSet())
        assertEquals(setOf("s3", "s5"), result.song.orEmpty().map { it.id }.toSet())
    }

    @Test
    fun `search matches on artist name`() {
        val result = offlineSearch("artist b", saved)
        assertEquals(listOf("ar2"), result.artist.orEmpty().map { it.id })
    }

    @Test
    fun `search with a blank query returns nothing`() {
        val result = offlineSearch("  ", saved)
        assertTrue(result.artist.isNullOrEmpty())
        assertTrue(result.album.isNullOrEmpty())
        assertTrue(result.song.isNullOrEmpty())
    }

    @Test
    fun `saved song lookup finds by id`() {
        assertEquals("s3", offlineSong("s3", saved)?.id)
        assertNull(offlineSong("missing", saved))
    }
}
