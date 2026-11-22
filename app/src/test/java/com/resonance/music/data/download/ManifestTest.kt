package com.resonance.music.data.download

import com.google.gson.Gson
import com.resonance.music.data.api.models.SongItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ManifestTest {
    private fun song(id: String, title: String, artist: String? = "Artist", album: String? = "Album") =
        SongItem(id, title, artist = artist, album = album, track = 3, suffix = "flac")

    @Test
    fun `a manifest line round trips a path and its full song`() {
        val entry = ManifestEntry("Resonance/Artist/Album/03 - A Song [0123456789ab].flac", song("s1", "A Song"))
        val parsed = parseManifestLine(manifestLine(entry))!!
        assertEquals(entry.path, parsed.path)
        assertEquals(entry.song, parsed.song)
    }

    @Test
    fun `metadata survives the round trip including nulls and unicode`() {
        val song = SongItem("id-2", "歌曲🎵 title", artist = "Ø Artist", album = null, duration = 266, starred = null)
        val parsed = parseManifestLine(manifestLine(ManifestEntry("p", song)))!!
        assertEquals(song, parsed.song)
    }

    @Test
    fun `blank and malformed lines are skipped rather than failing the import`() {
        assertNull(parseManifestLine(""))
        assertNull(parseManifestLine("   "))
        assertNull(parseManifestLine("not json at all"))
        // A line with a path but no song cannot become a playable record.
        assertNull(parseManifestLine("""{"path":"Resonance/X/1.flac"}"""))
    }

    @Test
    fun `import creates records only for files that exist on the storage`() = runBlocking {
        val onDisk = setOf(
            "Resonance/Artist/Album/01 - One [aaaaaaaaaaaa].flac",
            "Resonance/Artist/Album/02 - Two [bbbbbbbbbbbb].flac"
        )
        val imported = mutableListOf<ManifestEntry>()
        val count = importManifestEntries(
            entries = sequenceOf(
                ManifestEntry(onDisk.first(), song("s1", "One")),
                ManifestEntry(onDisk.last(), song("s2", "Two")),
                ManifestEntry("Resonance/Artist/Album/03 - Missing [cccccccccccc].flac", song("s3", "Three"))
            ).asIterable(),
            fileExists = { onDisk.contains(it) },
            recordFor = { imported += it }
        )
        assertEquals(2, count)
        assertEquals(listOf("One", "Two"), imported.map { it.song.title })
    }

    @Test
    fun `import is idempotent across repeated runs`() = runBlocking {
        val entry = ManifestEntry("Resonance/Artist/Album/01 - One [aaaaaaaaaaaa].flac", song("s1", "One"))
        var calls = 0
        suspend fun run() {
            importManifestEntries(listOf(entry), { true }) { calls++ }
        }
        run(); run()
        // The importer reports every present file; the record layer dedupes.
        assertEquals(2, calls)
        assertTrue(Gson().fromJson(manifestLine(entry), ManifestEntry::class.java).song.title == "One")
    }
}
