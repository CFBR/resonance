package com.resonance.music.data.download

import com.resonance.music.data.api.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

class TransferPolicyTest {
    @Test fun `storage refusals fail the record but keep the queue moving`() {
        for (error in listOf(
            StorageOperationException("The storage refused this download"),
            StorageOperationException("The storage renamed the download and it cannot be tracked"),
            IllegalStateException("provider lost the document")
        )) {
            assertEquals(TransferFailure.FAIL_RECORD, classifyTransferFailure(error))
        }
    }

    @Test fun `transport errors retry the queue with the song still queued`() {
        for (error in listOf(
            SocketTimeoutException("timeout"),
            ConnectException("refused"),
            UnknownHostException("server"),
            IOException("connection reset")
        )) {
            assertEquals(TransferFailure.RETRY_QUEUE, classifyTransferFailure(error))
        }
    }

    @Test fun `pending count covers only songs that still need a transfer`() {
        val song = SongItem("1", "A Song")
        val records = listOf(
            OfflineRecord("k1", "a", song),                                                   // queued, needs transfer
            OfflineRecord("k2", "a", song, relativePath = "Resonance/X/1.mp3"),             // ready
            OfflineRecord("k3", "a", song, downloadId = 7),                                  // DownloadManager owns it
            OfflineRecord("k4", "a", song, error = "Could not download."),                  // failed
            OfflineRecord("k5", "a", song, wifiOnly = true),                                  // queued, Wi-Fi lane
            OfflineRecord("k6", "a", song, wifiOnly = true, relativePath = "Resonance/X/6.mp3")  // ready, Wi-Fi lane
        )
        assertEquals(1, pendingTransferCount(records))
        assertEquals(1, pendingTransferCount(records, wifiOnly = true))
    }

    @Test fun `space guard stops the run only when the volume is nearly full`() {
        val flac = 40L * 1024 * 1024
        assertFalse(spaceGuard(null, flac))                       // unknown space never blocks
        assertFalse(spaceGuard(MIN_FREE_BYTES + flac, flac))      // enough for the file and the margin
        assertTrue(spaceGuard(MIN_FREE_BYTES, flac))              // margin alone is gone
        assertTrue(spaceGuard(MIN_FREE_BYTES / 2, null))          // no size known, volume nearly full
        assertFalse(spaceGuard(MIN_FREE_BYTES * 2, null))         // no size known, room to spare
    }
}
