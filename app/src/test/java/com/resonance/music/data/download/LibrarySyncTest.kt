package com.resonance.music.data.download

import com.resonance.music.data.api.models.AlbumItem
import com.resonance.music.data.api.models.SongItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibrarySyncTest {
    private fun album(id: String) = AlbumItem(id, "Album $id")
    private fun song(id: String) = SongItem(id, "Song $id")

    @Test
    fun `every album is visited once across pages and songs are queued per page`() = runBlocking {
        val server = (1..450).associate { it to List(3) { n -> song("a$it-$n") } }
        val fetched = mutableSetOf<String>()
        val queued = mutableListOf<List<SongItem>>()
        val pageCalls = mutableListOf<Int>()

        val added = enumerateLibrary(
            listAlbums = { offset ->
                pageCalls += offset
                server.keys.drop(offset).take(200).map { album(it.toString()) }
            },
            albumSongs = { id ->
                fetched += id
                server[id.toInt()] ?: throw IllegalArgumentException("missing")
            },
            queueSongs = { songs ->
                queued += songs
                songs.size
            },
            keepGoing = { true },
            pageSize = 200
        )

        assertEquals(listOf(0, 200, 400), pageCalls)
        assertEquals(server.keys.map { it.toString() }.toSet(), fetched)
        assertEquals(server.size * 3, added)
        // One queue batch per page, each carrying that page's songs.
        assertEquals(3, queued.size)
        assertEquals(600, queued[0].size)
        assertEquals(600, queued[1].size)
        assertEquals(150, queued[2].size)
    }

    @Test
    fun `a short page ends the walk without another request`() = runBlocking {
        val pageCalls = mutableListOf<Int>()
        enumerateLibrary(
            listAlbums = { offset ->
                pageCalls += offset
                if (offset == 0) List(200) { album("a$it") } else List(50) { album("b$it") }
            },
            albumSongs = { listOf(song(it)) },
            queueSongs = { it.size },
            keepGoing = { true },
            pageSize = 200
        )
        assertEquals(listOf(0, 200), pageCalls)
    }

    @Test
    fun `an album that fails to load is skipped and the walk continues`() = runBlocking {
        val added = enumerateLibrary(
            listAlbums = { offset ->
                if (offset == 0) List(4) { album("$it") } else emptyList()
            },
            albumSongs = { id -> if (id == "2") throw IllegalStateException("server hiccup") else listOf(song(id)) },
            queueSongs = { it.size },
            keepGoing = { true },
            pageSize = 4
        )
        assertEquals(3, added)
    }

    @Test
    fun `the walk stops early when keepGoing reports the destination is full`() = runBlocking {
        var visits = 0
        val added = enumerateLibrary(
            listAlbums = { offset ->
                if (offset == 0) List(200) { album("a$it") } else emptyList()
            },
            albumSongs = { id -> listOf(song(id)) },
            queueSongs = { it.size },
            keepGoing = { visits++ == 0 },
            pageSize = 200
        )
        // The check happens per album: the first passes, the second stops the
        // walk, and the half-gathered page is dropped rather than queued.
        assertEquals(0, added)
        assertEquals(2, visits)
    }

    @Test
    fun `progress reports pages scanned and songs queued`() = runBlocking {
        val progress = mutableListOf<Pair<Int, Int>>()
        enumerateLibrary(
            listAlbums = { offset ->
                if (offset == 0) List(2) { album("$it") } else emptyList()
            },
            albumSongs = { id -> listOf(song(id), song(id + "x")) },
            queueSongs = { it.size },
            keepGoing = { true },
            onProgress = { scanned, queuedTotal -> progress += scanned to queuedTotal },
            pageSize = 2
        )
        // One report per album plus one after the page's songs are queued.
        assertEquals(listOf(1 to 0, 2 to 0, 2 to 4), progress)
    }

    @Test
    fun `offline totals split track statuses into ready queued and failed`() {
        val song = SongItem("1", "A Song")
        val track = { status: OfflineStatus ->
            OfflineTrack(OfflineRecord("k", "a", song, relativePath = "Resonance/X/1.flac"), status)
        }
        val totals = offlineTotals(
            listOf(
                track(OfflineStatus.READY),
                track(OfflineStatus.QUEUED),
                track(OfflineStatus.DOWNLOADING),
                track(OfflineStatus.FAILED)
            )
        )
        assertEquals(1, totals.ready)
        assertEquals(2, totals.queued)
        assertEquals(1, totals.failed)
        assertEquals(4, totals.known)
    }

    @Test
    fun `totals count only songs this device has seen`() = runBlocking {
        assertTrue(offlineTotals(emptyList<OfflineTrack>()).let { it.ready == 0 && it.queued == 0 && it.failed == 0 })
    }
}
