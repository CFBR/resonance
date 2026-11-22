package com.resonance.music.playback

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScrobbleQueueTest {
    private fun entry(id: String, time: Long = 1000, submission: Boolean = true) =
        ScrobbleEntry(id, submission, time)

    @Test
    fun `entries keep their order and survive a store round trip`() {
        val store = FakeStore()
        val queue = ScrobbleQueue(store::load, store::save)
        queue.add(entry("s1", 100))
        queue.add(entry("s2", 200))
        queue.add(entry("s3", 300))
        // A new instance over the same store is the process-death case.
        val revived = ScrobbleQueue(store::load, store::save)
        assertEquals(listOf("s1", "s2", "s3"), revived.entries().map { it.songId })
        assertEquals(listOf(100L, 200L, 300L), revived.entries().map { it.time })
    }

    @Test
    fun `a repeat of the last entry is ignored`() {
        val store = FakeStore()
        val queue = ScrobbleQueue(store::load, store::save)
        assertTrue(queue.add(entry("s1", 100)))
        assertFalse(queue.add(entry("s1", 100)))
        assertEquals(1, queue.entries().size)
        // A different song is accepted, and the same song after it is not.
        assertTrue(queue.add(entry("s2", 200)))
        assertFalse(queue.add(entry("s2", 200)))
        assertEquals(2, queue.entries().size)
    }

    @Test
    fun `removal drops exactly the sent entries`() {
        val store = FakeStore()
        val queue = ScrobbleQueue(store::load, store::save)
        queue.add(entry("s1"))
        queue.add(entry("s2"))
        queue.add(entry("s3"))
        queue.remove(listOf(entry("s1"), entry("s2")))
        assertEquals(listOf("s3"), queue.entries().map { it.songId })
    }

    @Test
    fun `corrupt store contents start an empty queue instead of crashing`() {
        val store = FakeStore()
        store.saved = "not json {{"
        val queue = ScrobbleQueue(store::load, store::save)
        assertTrue(queue.entries().isEmpty())
    }

    @Test
    fun `flush sends in order and keeps everything after a failure`() = runBlocking {
        val sent = mutableListOf<ScrobbleEntry>()
        val completed = flushScrobbles(
            listOf(entry("s1"), entry("s2"), entry("s3")),
            send = { sent += it; it.songId != "s2" }
        )
        assertFalse(completed)
        assertEquals(listOf("s1", "s2"), sent.map { it.songId })
        // The caller learns the sent prefix so it can remove only that.
        assertEquals(2, sent.size)
    }

    @Test
    fun `a fully successful flush reports completion`() = runBlocking {
        val completed = flushScrobbles(listOf(entry("s1"), entry("s2")), send = { true })
        assertTrue(completed)
    }

    private class FakeStore {
        var saved: String? = null
        fun load(): String? = saved
        fun save(value: String) { saved = value }
    }
}
