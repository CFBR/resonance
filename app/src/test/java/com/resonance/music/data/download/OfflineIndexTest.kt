package com.resonance.music.data.download

import com.resonance.music.data.api.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineIndexTest {
    private fun track(songId: String, status: OfflineStatus = OfflineStatus.READY): OfflineTrack =
        OfflineTrack(
            record = OfflineRecord(
                key = "k-$songId-$status", account = "acc",
                song = SongItem(id = songId, title = "t-$songId", albumId = null, artistId = null),
                relativePath = "Relic Songs/$songId.flac"
            ),
            status = status
        )

    @Test
    fun `only ready tracks enter the index`() {
        val index = buildReadyIndex(
            listOf(
                track("ready"),
                track("queued", status = OfflineStatus.QUEUED),
                track("failed", status = OfflineStatus.FAILED)
            )
        )
        assertEquals(setOf("ready"), index.keys)
    }

    @Test
    fun `the first ready track wins a duplicate song id`() {
        val first = track("dup")
        val second = track("dup")
        val index = buildReadyIndex(listOf(first, second))
        assertSame(first, index["dup"])
    }

    @Test
    fun `an empty library indexes to nothing`() {
        assertTrue(buildReadyIndex(emptyList()).isEmpty())
    }

    @Test
    fun `a missing song id resolves to no uri`() {
        val index = buildReadyIndex(listOf(track("saved")))
        assertNull(index["other"])
    }

    @Test
    fun `download manager progress polls fast, everything else sleeps`() {
        assertEquals(2_000L, pollDelayMs(listOf(track("a", status = OfflineStatus.DOWNLOADING))))
        assertEquals(60_000L, pollDelayMs(listOf(track("q", status = OfflineStatus.QUEUED))))
        assertEquals(60_000L, pollDelayMs(listOf(track("ready"))))
        assertEquals(60_000L, pollDelayMs(emptyList()))
    }
}
