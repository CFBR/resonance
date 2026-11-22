package com.resonance.music.data.download

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CancellationException

@EntryPoint
@InstallIn(SingletonComponent::class)
interface DownloadWorkerDependencies {
    fun downloader(): SongDownloader
    fun notifications(): DownloadNotifications
}

class DownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val account = inputData.getString("account") ?: return Result.failure()
        val dependencies = EntryPointAccessors.fromApplication(
            applicationContext, DownloadWorkerDependencies::class.java
        )
        val downloader = dependencies.downloader()
        val favorites = inputData.getBoolean("favorites", false)
        return try {
            if (!favorites) beginForeground(downloader.pendingCount(account))
            if (favorites) downloader.syncFavorites(account)
            else downloader.processQueue(account, inputData.getBoolean("wifiOnly", false))
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.retry()
        } finally {
            if (!favorites) dependencies.notifications().clear()
        }
    }

    /**
     * A foreground worker survives Doze and OEM battery management for the
     * hours a large fill can take. When the system refuses a foreground start,
     * the transfer continues as ordinary background work instead of failing.
     */
    private suspend fun beginForeground(remaining: Int) {
        val notifications = EntryPointAccessors.fromApplication(
            applicationContext, DownloadWorkerDependencies::class.java
        ).notifications()
        val info = ForegroundInfo(
            DownloadNotifications.ID,
            notifications.progress(null, remaining),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        try {
            setForeground(info)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (_: Exception) {
            // Android can refuse a foreground start from the background; the
            // transfer continues as ordinary background work instead.
        }
    }
}
