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

private enum class WorkerMode { TRANSFERS, FAVORITES, LIBRARY, EXPORT, IMPORT }

class DownloadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val account = inputData.getString("account") ?: return Result.failure()
        val dependencies = EntryPointAccessors.fromApplication(
            applicationContext, DownloadWorkerDependencies::class.java
        )
        val downloader = dependencies.downloader()
        val notifications = dependencies.notifications()
        val mode = when {
            inputData.getBoolean("library", false) -> WorkerMode.LIBRARY
            inputData.getBoolean("export", false) -> WorkerMode.EXPORT
            inputData.getBoolean("import", false) -> WorkerMode.IMPORT
            inputData.getBoolean("favorites", false) -> WorkerMode.FAVORITES
            else -> WorkerMode.TRANSFERS
        }
        return try {
            when (mode) {
                WorkerMode.LIBRARY -> {
                    beginForeground(DownloadNotifications.ID_LIBRARY, notifications.libraryProgress(0, 0))
                    downloader.syncLibrary(account)
                }
                WorkerMode.FAVORITES -> downloader.syncFavorites(account)
                WorkerMode.EXPORT -> {
                    beginForeground(DownloadNotifications.ID_EXPORT, notifications.exportProgress(0))
                    downloader.exportManifest(account)
                }
                WorkerMode.IMPORT -> {
                    beginForeground(DownloadNotifications.ID_IMPORT, notifications.importProgress(0))
                    downloader.importManifest()
                }
                WorkerMode.TRANSFERS -> {
                    beginForeground(DownloadNotifications.ID, notifications.progress(null, downloader.pendingCount(account)))
                    downloader.processQueue(account, inputData.getBoolean("wifiOnly", false))
                }
            }
            Result.success()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            Result.retry()
        } finally {
            when (mode) {
                WorkerMode.LIBRARY -> notifications.clearLibrary()
                WorkerMode.EXPORT -> notifications.clearExport()
                WorkerMode.IMPORT -> notifications.clearImport()
                WorkerMode.TRANSFERS -> notifications.clear()
                WorkerMode.FAVORITES -> {}
            }
        }
    }

    /**
     * A foreground worker survives Doze and OEM battery management for the
     * hours a large fill can take. When the system refuses a foreground start,
     * the work continues in the background instead of failing.
     */
    private suspend fun beginForeground(notificationId: Int, notification: android.app.Notification) {
        val info = ForegroundInfo(
            notificationId,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        try {
            setForeground(info)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
        }
    }
}
