package com.resonance.music.ui.screens.home

import com.resonance.music.data.api.models.AlbumItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class HomeShelvesTest {
    private fun album(id: String, name: String, coverArt: String? = null) =
        AlbumItem(id = id, name = name, artist = "Artist $id", coverArt = coverArt)

    private val recent = listOf(album("r1", "Recent One", coverArt = "c-r1"), album("r2", "Recent Two"))
    private val newest = listOf(album("n1", "Newest One"))
    private val frequent = listOf(album("f1", "Frequent One"))
    private val random = listOf(album("x1", "Random One"))
    private val saved = listOf(album("s1", "Saved One"), album("s2", "Saved Two"))

    @Test
    fun `online keeps the four server shelves with their own titles`() {
        val shelves = homeShelves(
            HomeUiState(
                recentAlbums = recent, newestAlbums = newest,
                frequentAlbums = frequent, randomAlbums = random
            )
        )
        assertEquals(
            listOf("Recently played", "Newest additions", "Most played", "Random picks"),
            shelves.map { it.title }
        )
        assertEquals(recent, shelves[0].albums)
        assertEquals(random, shelves[3].albums)
    }

    @Test
    fun `offline drops the shelves the saved copy cannot back`() {
        val shelves = homeShelves(
            HomeUiState(savedAlbums = saved, randomAlbums = random, fromSavedAlbums = true)
        )
        assertEquals(listOf("Saved albums", "Random picks"), shelves.map { it.title })
        assertEquals(saved, shelves[0].albums)
        assertEquals(random, shelves[1].albums)
    }

    @Test
    fun `offline never claims a play history it does not have`() {
        val titles = homeShelves(
            HomeUiState(recentAlbums = recent, newestAlbums = newest, frequentAlbums = frequent, fromSavedAlbums = true)
        ).map { it.title }
        assertEquals(listOf("Saved albums", "Random picks"), titles)
    }

    @Test
    fun `offline has no hero, because it cannot say what was played last`() {
        val state = HomeUiState(savedAlbums = saved, randomAlbums = random, fromSavedAlbums = true)
        assertNull(state.featured)
    }

    @Test
    fun `online the hero is the first album with art`() {
        val state = HomeUiState(recentAlbums = recent, newestAlbums = newest)
        assertNotNull(state.featured)
        assertEquals("Recent One", state.featured?.name)
        assertEquals("c-r1", state.featured?.coverArt)
    }
}
