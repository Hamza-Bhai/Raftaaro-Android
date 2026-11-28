package io.github.hamzabhai.raftaaro.download

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import io.github.hamzabhai.raftaaro.core.Job
import io.github.hamzabhai.raftaaro.core.progressText
import io.github.hamzabhai.raftaaro.graph
import io.github.hamzabhai.raftaaro.ui.main.MainActivity

object Notifications {
    const val PROGRESS_ID = 1
    private const val CH_PROGRESS = "downloads"
    private const val CH_DONE = "finished"

    fun createChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_PROGRESS, "Downloads in progress", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Finished downloads", NotificationManager.IMPORTANCE_DEFAULT))
    }

    private fun openApp(ctx: Context): PendingIntent = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    /** The ongoing summary shown while the service runs, with `text` from [progressText]. */
    fun progress(ctx: Context, text: String, percent: Int?): Notification {
        createChannels(ctx)
        val pauseAll = PendingIntent.getService(
            ctx, 1, Intent(ctx, DownloadService::class.java).setAction(DownloadService.ACTION_PAUSE_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(ctx, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Raftaaro")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent ?: 0, percent == null)
            .setContentIntent(openApp(ctx))
            .addAction(0, "Pause all", pauseAll)
            .build()
    }

    /** "Download finished: <title>", opening the file when tapped. */
    fun finished(ctx: Context, job: Job) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        createChannels(ctx)
        val open = job.savedUri?.let {
            PendingIntent.getActivity(ctx, job.id.hashCode(), ctx.graph.saver.open(it), PendingIntent.FLAG_IMMUTABLE)
        } ?: openApp(ctx)
        val n = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Download finished")
            .setContentText(job.fileName ?: job.title)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        runCatching { NotificationManagerCompat.from(ctx).notify(job.id.hashCode(), n) }
    }
}
