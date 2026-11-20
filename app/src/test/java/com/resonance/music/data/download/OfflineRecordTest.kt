package com.resonance.music.data.download

import com.google.gson.Gson
import com.resonance.music.data.api.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OfflineRecordTest {
    private val song = SongItem("song-1", "A Song", artist = "Artist", album = "Album")
    private val gson = Gson()

    @Test fun `legacy records without a relative path deserialize with null`() {
        val legacy = """{"key":"k","account":"a","song":{"id":"song-1","title":"A Song"},"downloadId":5,"wifiOnly":false}"""
        val record = gson.fromJson(legacy, OfflineRecord::class.java)
        assertEquals(5L, record.downloadId)
        assertNull(record.relativePath)
    }

    @Test fun `relative path survives a serialization round trip`() {
        val record = OfflineRecord("k", "a", song, relativePath = "Resonance/Artist/Album/01 - A Song [0123456789ab].flac")
        val copy = gson.fromJson(gson.toJson(record), OfflineRecord::class.java)
        assertEquals(record, copy)
        assertEquals("Resonance/Artist/Album/01 - A Song [0123456789ab].flac", copy.relativePath)
    }

    @Test fun `download manager records keep working unchanged`() {
        val record = OfflineRecord("k", "a", song, downloadId = 9)
        assertNull(record.relativePath)
        assertEquals(9L, record.downloadId)
    }
}
