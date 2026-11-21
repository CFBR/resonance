package com.resonance.music.data.download

import java.io.IOException

/**
 * A failure the storage itself caused: the folder is unreachable, the provider
 * refused or renamed a document, or writing the bytes failed. The song is
 * marked failed and the queue moves on to the next song.
 *
 * Transport errors (connection drops, timeouts) are plain IOExceptions and take
 * the opposite path: the run stops, the song stays queued, and WorkManager
 * retries with backoff. That distinction is what keeps one dead zone or one
 * bad server from marking an entire large fill as failed.
 */
class StorageOperationException(message: String) : IOException(message)

sealed interface TransferFailure {
    /** Stop the run and let WorkManager retry with backoff; the song stays queued. */
    object RETRY_QUEUE : TransferFailure

    /** Record the failure on this song and move to the next. */
    object FAIL_RECORD : TransferFailure
}

internal fun classifyTransferFailure(error: Throwable): TransferFailure = when (error) {
    is StorageOperationException -> TransferFailure.FAIL_RECORD
    is IOException -> TransferFailure.RETRY_QUEUE
    else -> TransferFailure.FAIL_RECORD
}

/** Songs that still need a transfer, for the progress notification. */
internal fun pendingTransferCount(records: List<OfflineRecord>, wifiOnly: Boolean = false): Int =
    records.count { it.wifiOnly == wifiOnly && it.error == null && it.downloadId < 0 && it.relativePath == null }

/** Margin kept free on the storage volume so the system and other apps stay usable. */
const val MIN_FREE_BYTES: Long = 200L * 1024 * 1024

/**
 * True when the destination volume is too full to keep downloading: when the
 * next file would not leave the safety margin, or when the volume is nearly
 * full even without knowing the next file's size. Unknown space never blocks.
 */
internal fun spaceGuard(freeBytes: Long?, nextFileBytes: Long?): Boolean {
    if (freeBytes == null || freeBytes <= 0L) return false
    return freeBytes < (nextFileBytes ?: 0L) + MIN_FREE_BYTES
}
