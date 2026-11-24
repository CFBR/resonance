package com.resonance.music.data.download

import com.resonance.music.data.api.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val summary = artworkSummary(listOf(song("a", "ar-1"), song("b", "ar-1"), song("c", "ar-2")), isCached = { false })

        assertEquals(0, summary.saved)
        assertEquals(2, summary.total)
        assertEquals(2, summary.missing)
    }

    @Test
    fun `the summary counts what is already on the device`() {
        val cached = setOf("ar-1")
        val summary = artworkSummary(listOf(song("a", "ar-1"), song("b", "ar-2")), isCached = { it in cached })

        assertEquals(1, summary.saved)
        assertEquals(2, summary.total)
        assertEquals(1, summary.missing)
    }

    @Test
    fun `a complete library reports nothing missing`() {
        val summary = artworkSummary(listOf(song("a", "ar-1")), isCached = { true })

        assertEquals(1, summary.saved)
        assertEquals(0, summary.missing)
    }

    @Test
    fun `songs without covers count towards neither number`() {
        val summary = artworkSummary(listOf(song("a", null), song("b", "  "), song("c", "ar-1")), isCached = { false })

        assertEquals(0, summary.saved)
        assertEquals(1, summary.total)
    }

    @Test
    fun `a cover padded with spaces is the cover it names`() {
        val summary = artworkSummary(listOf(song("a", " ar-1 ")), isCached = { it == "ar-1" })

        assertEquals(1, summary.saved)
        assertEquals(1, summary.total)
    }

    @Test
    fun `covers the server does not have are left out of the total`() {
        val summary = artworkSummary(
            songs = listOf(song("a", "ar-1"), song("b", "ar-2")),
            isCached = { false },
            isAbsent = { it == "ar-2" }
        )

        assertEquals(0, summary.saved)
        assertEquals(1, summary.total)
    }

    @Test
    fun `a missing image is a 404, not a rate limit`() {
        assertTrue(imageIsAbsent(404))
        assertTrue(imageIsAbsent(410))
        assertFalse(imageIsAbsent(429))
        assertFalse(imageIsAbsent(408))
        assertFalse(imageIsAbsent(425))
        assertFalse(imageIsAbsent(500))
        assertFalse(imageIsAbsent(null))
    }

    @Test
    fun `a pass gives up after a run of failures`() {
        val run = FailureRun(limit = 3)

        assertFalse(run.failure())
        assertFalse(run.failure())
        assertTrue(run.failure())
        assertTrue(run.exhausted())
    }

    @Test
    fun `one cover that arrives clears the run`() {
        val run = FailureRun(limit = 3)

        run.failure()
        run.failure()
        run.success()
        assertFalse(run.failure())
        assertFalse(run.exhausted())
    }

    @Test
    fun `a fresh run has not given up`() {
        assertFalse(FailureRun(limit = 3).exhausted())
    }
}
