package com.resonance.music.data.repository

import com.resonance.music.data.api.models.PlaylistDetail
import com.resonance.music.data.api.models.PlaylistItem
import com.resonance.music.data.api.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaylistCacheCodecTest {
    private val playlist = PlaylistItem(
        id = "pl-1", name = "Road Trip", songCount = 12, duration = 2400,
        owner = "hsnadmin", coverArt = "pl-1", comment = "kept"
    )
    private val song = SongItem(id = "s-1", title = "First Light", album = "Dawn", artist = "Aurora", duration = 180)

    @Test
    fun `summaries round trip with every field kept`() {
        assertEquals(listOf(playlist), decodePlaylists(encodePlaylists(listOf(playlist))))
    }

    @Test
    fun `an empty cache reads as no playlists`() {
        assertTrue(decodePlaylists(null).isEmpty())
        assertTrue(decodePlaylists("").isEmpty())
        assertTrue(decodePlaylists("   ").isEmpty())
        assertTrue(decodePlaylists(encodePlaylists(emptyList())).isEmpty())
    }

    @Test
    fun `a corrupt cache reads as no playlists instead of throwing`() {
        assertTrue(decodePlaylists("{not json").isEmpty())
        assertTrue(decodePlaylists("""{"id":"pl-1"}""").isEmpty())
    }

    @Test
    fun `entries without an id are dropped`() {
        val json = """[{"id":"","name":"Broken"},{"id":"pl-9","name":"Fine"}]"""
        assertEquals(listOf("pl-9"), decodePlaylists(json).map { it.id })
    }

    @Test
    fun `detail round trips with its songs in order`() {
        val detail = PlaylistDetail(
            id = "pl-1", name = "Road Trip", songCount = 2,
            entry = listOf(song, song.copy(id = "s-2", title = "Second Wave"))
        )
        val decoded = decodePlaylistDetail(encodePlaylistDetail(detail))!!
        assertEquals("Road Trip", decoded.name)
        assertEquals(listOf("s-1", "s-2"), decoded.entry!!.map { it.id })
        assertEquals("First Light", decoded.entry!!.first().title)
    }

    @Test
    fun `a detail without songs still round trips`() {
        val decoded = decodePlaylistDetail(encodePlaylistDetail(PlaylistDetail(id = "pl-2", name = "Empty")))!!
        assertEquals("Empty", decoded.name)
        assertNull(decoded.entry)
    }

    @Test
    fun `a missing or corrupt detail reads as nothing`() {
        assertNull(decodePlaylistDetail(null))
        assertNull(decodePlaylistDetail(""))
        assertNull(decodePlaylistDetail("{oops"))
        assertNull(decodePlaylistDetail("""{"id":"","name":"Broken"}"""))
    }
}
