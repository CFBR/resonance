package com.resonance.music.playback

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.resonance.music.data.repository.MusicRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ScrobbleWorkerDependencies {
    fun music(): MusicRepository
    fun queue(): ScrobbleQueue
}

/**
 * Drains the scrobble queue once connectivity returns. Entries leave in the
 * order they were played; whatever was sent before a failure is kept removed,
 * and the rest waits for the next attempt.
 */
class ScrobbleFlushWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val dependencies = EntryPointAccessors.fromApplication(applicationContext, ScrobbleWorkerDependencies::class.java)
        val queue = dependencies.queue()
        val entries = queue.entries()
        if (entries.isEmpty()) return Result.success()
        val sent = mutableListOf<ScrobbleEntry>()
        val completed = flushScrobbles(entries) { entry ->
            val delivered = try {
                dependencies.music().scrobble(entry.songId, entry.submission, entry.time)
                true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
            if (delivered) sent += entry
            delivered
        }
        queue.remove(sent)
        return if (completed) Result.success() else Result.retry()
    }
}
