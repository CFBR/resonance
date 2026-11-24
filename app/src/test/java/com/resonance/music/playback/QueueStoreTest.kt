package com.resonance.music.playback

import com.resonance.music.data.api.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueStoreTest {
    private class FakeStore {
        val values = mutableMapOf<String, String?>()
        var queueWrites = 0
        fun loadQueue(): String? = values["queue"]
        fun saveQueue(value: String?) { queueWrites++; values["queue"] = value }
        fun loadPlace(): String? = values["place"]
        fun savePlace(value: String?) { values["place"] = value }
        fun store() = QueueStore(::loadQueue, ::saveQueue, ::loadPlace, ::savePlace)
    }

    private fun song(id: String) = SongItem(id = id, title = "Title $id", artist = "Artist")

    @Test
    fun `the queue survives a store round trip`() {
        val store = FakeStore()
        store.store().saveSnapshot(QueueSnapshot("acc-1", listOf(song("a"), song("b"))))

        val restored = store.store().snapshot()

        assertEquals("acc-1", restored?.account)
        assertEquals(listOf("a", "b"), restored?.songs?.map { it.id })
        assertEquals("Title a", restored?.songs?.first()?.title)
    }

    @Test
    fun `the position survives a store round trip`() {
        val store = FakeStore()
        store.store().savePlace(QueuePlace(index = 12, positionMs = 61_000))

        assertEquals(QueuePlace(12, 61_000), store.store().place())
    }

    @Test
    fun `clearing the queue removes both keys`() {
        val store = FakeStore()
        val store1 = store.store()
        store1.saveSnapshot(QueueSnapshot("acc-1", listOf(song("a"))))
        store1.savePlace(QueuePlace(0, 5_000))
        store1.saveSnapshot(null)

        assertNull(store.store().snapshot())
        assertNull(store.store().place())
    }

    @Test
    fun `an empty queue is not worth restoring`() {
        val store = FakeStore()
        store.store().saveSnapshot(QueueSnapshot("acc-1", emptyList()))

        assertNull(store.store().snapshot())
    }

    @Test
    fun `mangled state reads as nothing rather than crashing`() {
        val store = FakeStore()
        store.values["queue"] = "not json"
        store.values["place"] = "{\"index\":-4}"

        assertNull(store.store().snapshot())
        assertNull(store.store().place())
    }

    @Test
    fun `an unchanged queue is not written again`() {
        val store = FakeStore()
        val queue = store.store()
        val snapshot = QueueSnapshot("acc-1", listOf(song("a")))
        queue.saveSnapshot(snapshot)
        queue.saveSnapshot(snapshot)

        assertEquals(1, store.queueWrites)
    }

    @Test
    fun `a different queue is written`() {
        val store = FakeStore()
        val queue = store.store()
        queue.saveSnapshot(QueueSnapshot("acc-1", listOf(song("a"))))
        queue.saveSnapshot(QueueSnapshot("acc-1", listOf(song("a"), song("b"))))

        assertEquals(2, store.queueWrites)
    }
}
