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
}
