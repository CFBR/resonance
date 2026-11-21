package com.resonance.music.data.download

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import androidx.core.app.NotificationCompat
import com.resonance.music.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The progress notification for download runs, shown while the transfer worker
 * holds the foreground. Low importance: progress without sound or heads-up.
 */
@Singleton
class DownloadNotifications @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    private fun ensureChannel() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
    }

    fun progress(current: String?, remaining: Int): Notification {
        ensureChannel()
        val text = when {
            remaining <= 0 -> "All songs saved"
            current != null -> "$remaining songs left, now: $current"
            else -> "$remaining songs queued"
        }
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val intent = PendingIntent.getActivity(
            context, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle("Downloading music")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(intent)
            .build()
    }

    fun update(current: String?, remaining: Int) {
        manager.notify(ID, progress(current, remaining))
    }

    fun clear() {
        manager.cancel(ID)
    }

    companion object {
        const val ID = 42
        const val CHANNEL_ID = "downloads"
    }
}
