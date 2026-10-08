package earth.walkthru.aqsync.data.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import earth.walkthru.aqsync.R
import earth.walkthru.aqsync.data.ReconnectState
import earth.walkthru.aqsync.data.SyncProgress
import earth.walkthru.aqsync.ui.MainActivity
import earth.walkthru.aqsync.ui.components.formatBytes
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the two notifications the app ever posts, so the foreground service and the WorkManager
 * worker show the same thing:
 *
 * - the **ongoing progress** notification while files are being pulled ("AQ-6b40 · 12/776 files ·
 *   1.8 MiB", current file, a Stop action), and
 * - the **summary** after a background sync that actually brought new files ("Synced 23 new files
 *   from AQ-6b40 · 1.1 MiB"), which opens the app on tap. Nothing is posted when nothing was new.
 *
 * Channels are created lazily and idempotently. Posting requires `POST_NOTIFICATIONS` on API 33+;
 * when it was denied the foreground service still runs (Android just hides its notification) and
 * the summary is skipped.
 */
@Singleton
class SyncNotifications @Inject constructor(@ApplicationContext private val context: Context) {

    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)

    fun ensureChannels() {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, context.getString(R.string.notif_channel_progress), NotificationManager.IMPORTANCE_LOW).apply {
                description = context.getString(R.string.notif_channel_progress_desc)
                setShowBadge(false)
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SUMMARY, context.getString(R.string.notif_channel_summary), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = context.getString(R.string.notif_channel_summary_desc)
            }
        )
    }

    /** Whether we may post at all (always true below API 33, where the permission does not exist). */
    val canPost: Boolean
        get() = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * Ongoing progress. [stopIntent] is what the Stop action fires (a service action or a
     * WorkManager cancel intent); null hides the action.
     */
    fun progress(deviceName: String?, progress: SyncProgress, stopIntent: PendingIntent?, reconnecting: ReconnectState? = null): Notification {
        ensureChannels()
        val name = deviceName ?: context.getString(R.string.notif_device)
        val title = when {
            reconnecting != null -> context.getString(R.string.notif_reconnecting, name, reconnecting.attempt, reconnecting.maxAttempts)
            progress.filesTotal > 0 -> context.getString(R.string.notif_progress, name, progress.filesDone, progress.filesTotal, formatBytes(progress.bytesDone))
            else -> context.getString(R.string.notif_preparing, name)
        }
        val current = progress.current
        val text = when {
            reconnecting != null -> context.getString(R.string.notif_link_lost)
            current != null -> current.name.substringAfterLast('/') + " · ${formatBytes(current.bytesDone)} / ${formatBytes(current.bytesTotal)}"
            progress.bytesTotal > 0 -> context.getString(R.string.files_bytes_of, formatBytes(progress.bytesDone), formatBytes(progress.bytesTotal))
            else -> context.getString(R.string.notif_listing)
        }
        val builder = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(openAppIntent())
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        if (progress.bytesTotal > 0 && reconnecting == null) {
            builder.setProgress(PROGRESS_MAX, (progress.fraction * PROGRESS_MAX).toInt(), false)
        } else {
            builder.setProgress(0, 0, true)
        }
        if (stopIntent != null) {
            builder.addAction(NotificationCompat.Action.Builder(0, context.getString(R.string.files_stop), stopIntent).build())
        }
        return builder.build()
    }

    /** Post the end-of-background-sync summary. Silent no-op when nothing was new or we may not post. */
    fun postSummary(deviceName: String?, newFiles: Int, newBytes: Long, failures: Int) {
        if (newFiles <= 0 && failures <= 0) return
        if (!canPost) return
        ensureChannels()
        val name = deviceName ?: context.getString(R.string.notif_device)
        val res = context.resources
        val title = if (newFiles > 0) res.getQuantityString(R.plurals.notif_synced, newFiles, newFiles, name, formatBytes(newBytes)) else context.getString(R.string.notif_problems, name)
        val text = if (failures > 0) res.getQuantityString(R.plurals.notif_failures, failures, failures) else context.getString(R.string.notif_verified)
        val n = NotificationCompat.Builder(context, CHANNEL_SUMMARY)
            .setSmallIcon(R.drawable.ic_stat_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openAppIntent())
            .build()
        try {
            manager.notify(SUMMARY_ID, n)
        } catch (e: SecurityException) {
            // Permission revoked between the check and the post; the summary is optional.
        }
    }

    fun cancelSummary() = manager.cancel(SUMMARY_ID)

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(context, REQ_OPEN, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val CHANNEL_PROGRESS = "sync_progress"
        const val CHANNEL_SUMMARY = "sync_summary"

        /** Notification id of the foreground service (user-started sync). */
        const val FOREGROUND_ID = 1001

        /** Notification id of the WorkManager foreground worker (auto-sync). */
        const val WORKER_ID = 1002
        const val SUMMARY_ID = 1003
        private const val REQ_OPEN = 10
        private const val PROGRESS_MAX = 1000
    }
}
