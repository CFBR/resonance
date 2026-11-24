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

    @Test
    fun `a quiet library doubles its pauses`() {
        assertEquals(60_000L, pollDelayMs(listOf(track("ready")), quietPolls = 0))
        assertEquals(120_000L, pollDelayMs(listOf(track("ready")), quietPolls = 1))
        assertEquals(240_000L, pollDelayMs(listOf(track("ready")), quietPolls = 2))
        assertEquals(480_000L, pollDelayMs(listOf(track("ready")), quietPolls = 3))
    }

    @Test
    fun `the pause stops growing at the cap`() {
        assertEquals(QUIET_POLL_CAP_MS, pollDelayMs(listOf(track("ready")), quietPolls = 5))
        assertEquals(QUIET_POLL_CAP_MS, pollDelayMs(listOf(track("ready")), quietPolls = 40))
    }

    @Test
    fun `an active transfer ignores the quiet count`() {
        val downloading = listOf(track("a", status = OfflineStatus.DOWNLOADING))
        assertEquals(2_000L, pollDelayMs(downloading, quietPolls = 0))
        assertEquals(2_000L, pollDelayMs(downloading, quietPolls = 9))
    }

    @Test
    fun `the record cache reuses a parse until the store is written`() {
        var parses = 0
        val cache = RecordsCache { account -> parses++; listOf(recordFor(account)) }

        val first = cache.records(revision = 1, account = "acc")
        val second = cache.records(revision = 1, account = "acc")

        assertEquals(first, second)
        assertEquals(1, parses)
    }

    @Test
    fun `a write forces the next read to re-parse`() {
        var parses = 0
        val cache = RecordsCache { _ -> parses++; listOf(recordFor("acc")) }

        cache.records(revision = 1, account = "acc")
        cache.records(revision = 2, account = "acc")

        assertEquals(2, parses)
    }

    @Test
    fun `another account does not share a cache entry`() {
        val cache = RecordsCache { account -> listOf(recordFor(account)) }

        assertEquals(listOf(recordFor("one")), cache.records(revision = 1, account = "one"))
        assertEquals(listOf(recordFor("two")), cache.records(revision = 1, account = "two"))
    }

    private fun recordFor(account: String): OfflineRecord = OfflineRecord(
        key = "k", account = account,
        song = SongItem(id = "s1", title = "Song", album = "Album", artist = "Artist")
    )
}
