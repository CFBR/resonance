package com.resonance.music.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RelativePathTest {
    @Test fun `a download path splits into its directory segments`() {
        assertEquals(
            listOf("Resonance", "Artist", "Album", "01 - Song [0123456789ab].flac"),
            relativeSegments("Resonance/Artist/Album/01 - Song [0123456789ab].flac")
        )
    }

    @Test fun `traversal and empty segments are rejected`() {
        for (bad in listOf("", "/absolute", "Resonance//Album", "Resonance/../secret", "a/./b", "..", "a/..")) {
            assertThrows(IllegalArgumentException::class.java) { relativeSegments(bad) }
        }
    }

    @Test fun `single file names are valid`() {
        assertEquals(listOf("file.flac"), relativeSegments("file.flac"))
    }
}
