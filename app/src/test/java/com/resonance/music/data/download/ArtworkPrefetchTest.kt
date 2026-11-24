package com.resonance.music.data.download

import com.resonance.music.data.api.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The artwork pass only needs the covers that are neither saved nor already
 * cached, once each, whatever the song list repeats.
 */
class ArtworkPrefetchTest {

    private fun song(id: String, cover: String?) =
        SongItem(id = id, title = id, coverArt = cover)

    @Test
    fun `covers are distinct and keep first-seen order`() {
        val songs = listOf(song("a", "c2"), song("b", "c1"), song("c", "c2"))
        val missing = missingArtworkCovers(songs) { false }
        assertEquals(listOf("c2", "c1"), missing)
    }

    @Test
    fun `already cached covers are skipped`() {
        val songs = listOf(song("a", "c1"), song("b", "c2"))
        val missing = missingArtworkCovers(songs) { cover -> cover == "c1" }
        assertEquals(listOf("c2"), missing)
    }

    @Test
    fun `songs without cover art are ignored`() {
        val missing = missingArtworkCovers(listOf(song("a", null))) { false }
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `blank covers are ignored`() {
        val missing = missingArtworkCovers(listOf(song("a", "  "))) { false }
        assertTrue(missing.isEmpty())
    }

    @Test
    fun `the summary counts each cover once`() {
        val summary = artworkSummary(listOf(song("a", "ar-1"), song("b", "ar-1"), song("c", "ar-2"))) { false }

        assertEquals(0, summary.saved)
        assertEquals(2, summary.total)
        assertEquals(2, summary.missing)
    }

    @Test
    fun `the summary counts what is already on the device`() {
        val cached = setOf("ar-1")
        val summary = artworkSummary(listOf(song("a", "ar-1"), song("b", "ar-2"))) { it in cached }

        assertEquals(1, summary.saved)
        assertEquals(2, summary.total)
        assertEquals(1, summary.missing)
    }

    @Test
    fun `a complete library reports nothing missing`() {
        val summary = artworkSummary(listOf(song("a", "ar-1"))) { true }

        assertEquals(1, summary.saved)
        assertEquals(0, summary.missing)
    }

    @Test
    fun `songs without covers count towards neither number`() {
        val summary = artworkSummary(listOf(song("a", null), song("b", "  "), song("c", "ar-1"))) { false }

        assertEquals(0, summary.saved)
        assertEquals(1, summary.total)
    }

    @Test
    fun `a cover padded with spaces is the cover it names`() {
        val summary = artworkSummary(listOf(song("a", " ar-1 "))) { it == "ar-1" }

        assertEquals(1, summary.saved)
        assertEquals(1, summary.total)
    }
}
