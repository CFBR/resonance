package com.resonance.music.data.repository

import com.resonance.music.data.api.models.AlbumItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeCacheCodecTest {
    private fun album(id: String, name: String = "Album $id") = AlbumItem(id = id, name = name, artist = "Artist")

    private fun shelves(
        recent: List<AlbumItem> = listOf(album("r1")),
        newest: List<AlbumItem> = listOf(album("n1")),
        frequent: List<AlbumItem> = listOf(album("f1")),
        random: List<AlbumItem> = listOf(album("d1"))
    ) = HomeShelves(recent = recent, newest = newest, frequent = frequent, random = random)

    @Test
    fun `live shelves survive a round trip`() {
        val decoded = decodeHomeShelves(encodeHomeShelves(shelves()))

        assertEquals(listOf("r1"), decoded?.recent?.map { it.id })
        assertEquals(listOf("n1"), decoded?.newest?.map { it.id })
        assertEquals(listOf("f1"), decoded?.frequent?.map { it.id })
        assertEquals(listOf("d1"), decoded?.random?.map { it.id })
    }

    @Test
    fun `the fields of an album survive a round trip`() {
        val decoded = decodeHomeShelves(
            encodeHomeShelves(
                HomeShelves(recent = listOf(AlbumItem(id = "a1", name = "Alive Or Just Breathing", artist = "Killswitch Engage", artistId = "ar-1", coverArt = "co-1", songCount = 12, year = 2002)))
            )
        )

        val album = decoded?.recent?.single()
        assertEquals("Alive Or Just Breathing", album?.name)
        assertEquals("Killswitch Engage", album?.artist)
        assertEquals("co-1", album?.coverArt)
        assertEquals(12, album?.songCount)
        assertEquals(2002, album?.year)
    }

    @Test
    fun `a fallback is not worth replaying`() {
        assertNull(decodeHomeShelves(encodeHomeShelves(HomeShelves(saved = listOf(album("s1")), fromSavedAlbums = true))))
    }

    @Test
    fun `an empty cache reads as nothing`() {
        assertNull(decodeHomeShelves(encodeHomeShelves(HomeShelves())))
        assertNull(decodeHomeShelves(null))
        assertNull(decodeHomeShelves("   "))
    }

    @Test
    fun `mangled json reads as nothing`() {
        assertNull(decodeHomeShelves("{not json"))
        assertNull(decodeHomeShelves("[\"not\", \"shelves\"]"))
    }
}
