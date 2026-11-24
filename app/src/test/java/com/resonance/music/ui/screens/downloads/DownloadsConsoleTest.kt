package com.resonance.music.ui.screens.downloads

import com.resonance.music.data.api.models.SongItem
import com.resonance.music.data.download.ArtworkSummary
import com.resonance.music.data.download.OfflineRecord
import com.resonance.music.data.download.OfflineStatus
import com.resonance.music.data.download.OfflineTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadsConsoleTest {
    private fun track(
        id: String,
        title: String,
        album: String? = "Album A",
        albumId: String? = "al-a",
        artist: String? = "Artist X",
        trackNo: Int? = null,
        coverArt: String? = null,
        status: OfflineStatus = OfflineStatus.READY
    ): OfflineTrack = OfflineTrack(
        record = OfflineRecord(
            key = "k-$id", account = "acc",
            song = SongItem(id = id, title = title, album = album, albumId = albumId, artist = artist, track = trackNo, coverArt = coverArt)
        ),
        status = status
    )

    @Test
    fun `empty search and all filter keep everything`() {
        val tracks = listOf(track("a", "First"), track("b", "Second", status = OfflineStatus.QUEUED))
        assertEquals(2, consoleGroups(tracks, "", DownloadFilter.ALL).sumOf { it.songs.size })
        assertEquals(2, consoleGroups(tracks, "   ", DownloadFilter.ALL).sumOf { it.songs.size })
    }

    @Test
    fun `search matches title artist or album case-insensitively`() {
        val tracks = listOf(
            track("a", "Letterbomb", album = "American Idiot", artist = "Green Day"),
            track("b", "Holiday", album = "American Idiot", artist = "Green Day"),
            track("c", "Letter", album = "Other", artist = "Someone Else")
        )
        assertEquals(listOf("Letterbomb"), consoleGroups(tracks, "letterb", DownloadFilter.ALL).flatMap { it.songs.map { s -> s.record.song.title } })
        assertEquals(2, consoleGroups(tracks, "green day", DownloadFilter.ALL).sumOf { it.songs.size })
        assertEquals(2, consoleGroups(tracks, "IDIO", DownloadFilter.ALL).sumOf { it.songs.size })
        assertTrue(consoleGroups(tracks, "zzz", DownloadFilter.ALL).isEmpty())
    }

    @Test
    fun `status buckets split the list`() {
        val tracks = listOf(
            track("a", "Saved"),
            track("b", "Waiting", status = OfflineStatus.QUEUED),
            track("c", "Moving", status = OfflineStatus.DOWNLOADING),
            track("d", "Broken", status = OfflineStatus.FAILED)
        )
        assertEquals(listOf("Saved"), consoleGroups(tracks, "", DownloadFilter.SAVED).flatMap { it.songs.map { s -> s.record.song.title } })
        // Same track number, so title order decides.
        assertEquals(listOf("Moving", "Waiting"), consoleGroups(tracks, "", DownloadFilter.WAITING).flatMap { it.songs.map { s -> s.record.song.title } })
        assertEquals(listOf("Broken"), consoleGroups(tracks, "", DownloadFilter.FAILED).flatMap { it.songs.map { s -> s.record.song.title } })
        assertEquals(4, consoleGroups(tracks, "", DownloadFilter.ALL).sumOf { it.songs.size })
    }

    @Test
    fun `songs of one album group together in track order`() {
        val tracks = listOf(
            track("a", "Track Two", trackNo = 2),
            track("b", "Track One", trackNo = 1),
            track("c", "Track Three", trackNo = 3, album = "Album B", albumId = "al-b", artist = "Artist X")
        )
        val groups = consoleGroups(tracks, "", DownloadFilter.ALL)
        assertEquals(2, groups.size)
        val albumA = groups.first { it.album == "Album A" }
        assertEquals(listOf("Track One", "Track Two"), albumA.songs.map { it.record.song.title })
    }

    @Test
    fun `songs without an album id each stand alone under their title`() {
        val tracks = listOf(
            track("a", "Lone Song", album = null, albumId = null),
            track("b", "Another Lone", album = null, albumId = null)
        )
        val groups = consoleGroups(tracks, "", DownloadFilter.ALL)
        assertEquals(listOf("Another Lone", "Lone Song"), groups.map { it.album })
        assertTrue(groups.all { it.songs.size == 1 })
    }

    @Test
    fun `groups sort by artist then album name`() {
        val tracks = listOf(
            track("a", "T", album = "Zeta", albumId = "z", artist = "Beta"),
            track("b", "T", album = "Alpha", albumId = "a", artist = "Beta"),
            track("c", "T", album = "Alpha", albumId = "a2", artist = "Aaa")
        )
        assertEquals(
            listOf("Alpha" to "Aaa", "Alpha" to "Beta", "Zeta" to "Beta"),
            consoleGroups(tracks, "", DownloadFilter.ALL).map { it.album to it.artist }
        )
    }

    @Test
    fun `the group cover comes from the first song that has one`() {
        val tracks = listOf(
            track("a", "No Art", coverArt = null),
            track("b", "Has Art", coverArt = "cov-1")
        )
        assertEquals("cov-1", consoleGroups(tracks, "", DownloadFilter.ALL).first().coverArt)
    }

    @Test
    fun `a search can empty the console`() {
        val tracks = listOf(track("a", "Only"))
        assertTrue(consoleGroups(tracks, "nope", DownloadFilter.ALL).isEmpty())
    }

    @Test
    fun `the artwork line counts what is saved`() {
        assertEquals(
            "1800 of 4494 covers saved, 2694 missing.",
            artworkLine(ArtworkSummary(saved = 1800, total = 4494))
        )
    }

    @Test
    fun `a complete set of covers says so`() {
        assertEquals("4494 of 4494 covers saved.", artworkLine(ArtworkSummary(4494, 4494)))
    }

    @Test
    fun `nothing to fetch yet is not a zero count`() {
        assertEquals("No covers needed yet.", artworkLine(ArtworkSummary(0, 0)))
    }
}
