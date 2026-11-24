package com.resonance.music.playback

import org.junit.Assert.assertEquals
import org.junit.Test

class BuiltQueueTest {
    private val songs = listOf("one", "two", "three", "four")

    @Test
    fun `every song that can play is kept in order`() {
        val built = builtQueue(songs, startIndex = 2) { it.uppercase() }

        assertEquals(listOf("ONE", "TWO", "THREE", "FOUR"), built.items)
        assertEquals(songs, built.songs)
        assertEquals(2, built.startIndex)
    }

    @Test
    fun `a dropped song before the start does not move the start`() {
        val built = builtQueue(songs, startIndex = 3) { if (it == "one") null else it.uppercase() }

        assertEquals(listOf("TWO", "THREE", "FOUR"), built.items)
        assertEquals(2, built.startIndex)
        assertEquals("FOUR", built.items[built.startIndex])
    }

    @Test
    fun `a dropped start song falls through to the next one that can play`() {
        val built = builtQueue(songs, startIndex = 1) { if (it == "two") null else it.uppercase() }

        assertEquals(listOf("ONE", "THREE", "FOUR"), built.items)
        assertEquals(1, built.startIndex)
        assertEquals("THREE", built.items[built.startIndex])
    }

    @Test
    fun `a start past the end lands on the last item`() {
        val built = builtQueue(songs, startIndex = 9) { it }

        assertEquals(3, built.startIndex)
        assertEquals("four", built.items[built.startIndex])
    }

    @Test
    fun `nothing playable leaves an empty queue`() {
        val built = builtQueue(songs, startIndex = 1) { null }

        assertEquals(emptyList<String>(), built.items)
        assertEquals(emptyList<String>(), built.songs)
        assertEquals(0, built.startIndex)
    }
}
