package com.resonance.music.data.repository

import com.resonance.music.data.api.models.LyricsLine
import com.resonance.music.data.api.models.LyricsResult
import com.resonance.music.data.api.models.StructuredLyrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The resolution order the lyrics screen shows is also the order the cache
 * stores: synced beats unsynced beats the legacy text endpoint, and an entry
 * without lines is not a lyrics entry at all.
 */
class LyricsResolveTest {

    private fun structured(synced: Boolean, vararg lines: String) =
        StructuredLyrics(lang = "en", synced = synced, line = lines.map { LyricsLine(start = if (synced) 100L else null, value = it) })

    @Test
    fun `synced structured lyrics win over unsynced`() {
        val resolved = resolveLyrics(listOf(structured(false, "plain"), structured(true, "first", "second")), null)
        assertTrue(resolved!!.synced)
        assertEquals(listOf(100L, 100L), resolved.startMs)
        assertEquals(listOf("first", "second"), resolved.text)
    }

    @Test
    fun `unsynced structured lyrics become plain text`() {
        val resolved = resolveLyrics(listOf(structured(false, "a", "b")), null)
        assertFalse(resolved!!.synced)
        assertTrue(resolved.startMs.isEmpty())
        assertEquals(listOf("a", "b"), resolved.text)
    }

    @Test
    fun `empty structured entries fall through to legacy`() {
        val legacy = LyricsResult(artist = "A", title = "T", value = "line one\nline two")
        val resolved = resolveLyrics(listOf(structured(true)), legacy)
        assertEquals(listOf("line one", "line two"), resolved!!.text)
        assertFalse(resolved.synced)
    }

    @Test
    fun `legacy blank text is not lyrics`() {
        assertNull(resolveLyrics(emptyList(), LyricsResult(value = "  ")))
    }

    @Test
    fun `nothing anywhere resolves to null`() {
        assertNull(resolveLyrics(emptyList(), null))
    }
}
