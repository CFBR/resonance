package com.resonance.music.data.download

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun `an exact name wins over a case variant`() {
        val children = mapOf("Alive Or Just Breathing" to "id-upper", "Alive or Just Breathing" to "id-lower")
        assertEquals("id-lower", matchChild(children, "Alive or Just Breathing"))
        assertEquals("id-upper", matchChild(children, "Alive Or Just Breathing"))
    }

    @Test fun `a case difference still finds the file`() {
        val children = mapOf("InnerPartySystem" to "id-1")
        assertEquals("id-1", matchChild(children, "Innerpartysystem"))
        assertEquals("id-1", matchChild(children, "INNERPARTYSYSTEM"))
    }

    @Test fun `letter case in other alphabets matches too`() {
        val children = mapOf("Sigur Rós" to "id-1")
        assertEquals("id-1", matchChild(children, "sigur rós"))
        assertEquals("id-1", matchChild(children, "SIGUR RÓS"))
    }

    @Test fun `an absent name is a miss`() {
        assertNull(matchChild(mapOf("Alive Or Just Breathing" to "id-1"), "Homesick"))
        assertNull(matchChild(emptyMap(), "Anything"))
    }

    @Test fun `an ambiguous case variant is a miss, not a guess`() {
        // Only possible on a case-sensitive provider; two folders differ by case.
        val children = mapOf("InnerPartySystem" to "id-1", "Innerpartysystem" to "id-2")
        assertNull(matchChild(children, "INNERPARTYSYSTEM"))
    }
}
