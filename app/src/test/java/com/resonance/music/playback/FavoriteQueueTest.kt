package com.resonance.music.playback

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteQueueTest {
    private class FakeStore {
        var saved: String? = null
        fun load(): String? = saved
        fun save(value: String) { saved = value }
    }

    @Test
    fun `a queued change survives a store round trip`() {
        val store = FakeStore()
        FavoriteQueue(store::load, store::save).record("s1", true)

        assertEquals(mapOf("s1" to true), FavoriteQueue(store::load, store::save).pending())
    }

    @Test
    fun `the last state the user wanted wins`() {
        val store = FakeStore()
        val queue = FavoriteQueue(store::load, store::save)
        queue.record("s1", true)
        queue.record("s1", false)

        assertEquals(mapOf("s1" to false), queue.pending())
    }

    @Test
    fun `the same state recorded twice does not schedule again`() {
        val store = FakeStore()
        var scheduled = 0
        val queue = FavoriteQueue(store::load, store::save) { scheduled++ }
        queue.record("s1", true)
        queue.record("s1", true)

        assertEquals(1, scheduled)
    }

    @Test
    fun `removing what the server accepted empties the queue`() {
        val store = FakeStore()
        val queue = FavoriteQueue(store::load, store::save)
        queue.record("s1", true)
        queue.record("s2", false)
        queue.remove(listOf("s1"))

        assertEquals(mapOf("s2" to false), queue.pending())
        assertFalse(queue.isEmpty())
        queue.remove(listOf("s2"))
        assertTrue(queue.isEmpty())
    }

    @Test
    fun `a mangled store reads as empty rather than crashing`() {
        val store = FakeStore()
        store.saved = "[\"not\", \"an\", \"object\"]"

        assertTrue(FavoriteQueue(store::load, store::save).isEmpty())
    }

    @Test
    fun `a flush stops at the first failure and reports what landed`() = runBlocking {
        val pending = linkedMapOf("s1" to true, "s2" to false, "s3" to true)
        val attempts = mutableListOf<String>()

        val delivered = flushFavorites(pending) { songId, _ ->
            attempts += songId
            songId != "s2"
        }

        assertEquals(listOf("s1"), delivered)
        assertEquals(listOf("s1", "s2"), attempts)
    }

    @Test
    fun `a flush sends the state the user wanted`() = runBlocking {
        val sent = mutableListOf<Pair<String, Boolean>>()

        flushFavorites(linkedMapOf("s1" to false, "s2" to true)) { songId, starred ->
            sent += songId to starred
            true
        }

        assertEquals(listOf("s1" to false, "s2" to true), sent)
    }
}
