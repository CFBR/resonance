package com.resonance.music.playback

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
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
    fun favorites(): FavoriteQueue
}

/**
 * Drains the writes that could not reach the server: scrobbles in the order they
 * were played, then favourite changes. Whatever a failure left behind waits for
 * the next attempt, which the scheduling below repeats with backoff.
 */
class ScrobbleFlushWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val dependencies = EntryPointAccessors.fromApplication(applicationContext, ScrobbleWorkerDependencies::class.java)
        val scrobblesSent = flushScrobbleQueue(dependencies)
        val favoritesSent = flushFavoriteQueue(dependencies)
        return if (scrobblesSent && favoritesSent) Result.success() else Result.retry()
    }

    private suspend fun flushScrobbleQueue(dependencies: ScrobbleWorkerDependencies): Boolean {
        val queue = dependencies.queue()
        val entries = queue.entries()
        if (entries.isEmpty()) return true
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
        return completed
    }

    private suspend fun flushFavoriteQueue(dependencies: ScrobbleWorkerDependencies): Boolean {
        val queue = dependencies.favorites()
        val pending = queue.pending()
        if (pending.isEmpty()) return true
        val delivered = flushFavorites(pending) { songId, starred ->
            try {
                if (starred) dependencies.music().star(songId) else dependencies.music().unstar(songId)
                true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                false
            }
        }
        queue.remove(delivered)
        return delivered.size == pending.size
    }
}

/**
 * Schedules the drain of queued writes. Unique, so a run of failures waits on
 * one attempt instead of queuing dozens, and constrained to a live network so
 * WorkManager does the waiting.
 */
internal fun scheduleQueuedWriteFlush(context: Context) {
    WorkManager.getInstance(context).enqueueUniqueWork(
        "queued_writes", ExistingWorkPolicy.KEEP,
        OneTimeWorkRequestBuilder<ScrobbleFlushWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    )
}
