package com.resonance.music.data.repository

import com.resonance.music.data.api.models.ArtistInfo2
import com.resonance.music.data.api.models.ArtistItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistInfoCacheCodecTest {
    private val info = ArtistInfo2(
        biography = "A band from somewhere, formed in a year.",
        largeImageUrl = "http://example.invalid/artist/large",
        similarArtist = listOf(ArtistItem(id = "a-2", name = "Someone Alike"))
    )

    @Test
    fun `info round trips with biography, image, and similar artists`() {
        val decoded = decodeArtistInfo(encodeArtistInfo(info))!!
        assertEquals(info.biography, decoded.biography)
        assertEquals(info.largeImageUrl, decoded.largeImageUrl)
        assertEquals(listOf("Someone Alike"), decoded.similarArtist!!.map { it.name })
    }

    @Test
    fun `a missing or blank cache reads as nothing`() {
        assertNull(decodeArtistInfo(null))
        assertNull(decodeArtistInfo(""))
        assertNull(decodeArtistInfo("   "))
    }

    @Test
    fun `a corrupt cache reads as nothing instead of throwing`() {
        assertNull(decodeArtistInfo("{not json"))
        assertNull(decodeArtistInfo("""{"biography": {"nested": true}}"""))
    }

    @Test
    fun `an entry with neither text nor similar artists is treated as missing`() {
        assertNull(decodeArtistInfo(encodeArtistInfo(ArtistInfo2())))
        assertNull(decodeArtistInfo(encodeArtistInfo(ArtistInfo2(biography = "  "))))
    }

    @Test
    fun `similar artists alone are worth keeping`() {
        val decoded = decodeArtistInfo(encodeArtistInfo(ArtistInfo2(similarArtist = info.similarArtist)))!!
        assertTrue(decoded.biography.isNullOrBlank())
        assertEquals(1, decoded.similarArtist!!.size)
    }
}
