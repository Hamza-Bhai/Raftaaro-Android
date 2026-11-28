package io.github.hamzabhai.raftaaro.download

import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import io.github.hamzabhai.raftaaro.core.JobStatus
import io.github.hamzabhai.raftaaro.core.progressText
import io.github.hamzabhai.raftaaro.graph
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps the app alive while downloads run, with one ongoing notification. The queue does the work;
 * this service only holds the foreground state and stops itself when nothing is left to do.
 */
class DownloadService : LifecycleService() {
    private var watcher: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        ServiceCompat.startForeground(
            this, Notifications.PROGRESS_ID, Notifications.progress(this, progressText(0, 0, null, 0, null), null),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        if (intent?.action == ACTION_PAUSE_ALL) {
            lifecycleScope.launch { graph.queue.pauseAll(null) }
        }
        if (watcher == null) watcher = lifecycleScope.launch {
            graph.jobs.jobs.collect { jobs ->
                val live = jobs.filter { it.status in LIVE_STATUSES }
                if (live.isEmpty()) {
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    return@collect
                }
                val total = live.sumOf { it.totalBytes ?: 0 }
                val percent = if (live.all { it.totalBytes != null } && total > 0) (live.sumOf { it.receivedBytes } * 100 / total).toInt() else null
                val running = live.count { it.status != JobStatus.QUEUED }
                val finishing = live.count { it.status == JobStatus.MERGING }
                val speed = live.filter { it.status == JobStatus.DOWNLOADING }.sumOf { it.speedBps }
                val left = if (live.all { it.totalBytes != null }) (total - live.sumOf { it.receivedBytes }).coerceAtLeast(0) else null
                val eta = if (left != null && speed > 0) left / speed else null
                val text = progressText(running, finishing, percent, speed, eta)
                runCatching {
                    NotificationManagerCompat.from(this@DownloadService)
                        .notify(Notifications.PROGRESS_ID, Notifications.progress(this@DownloadService, text, percent))
                }
            }
        }
        return START_NOT_STICKY
    }

    /** Android 15+: the daily dataSync time is used up. Pause cleanly instead of being killed. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        lifecycleScope.launch {
            graph.queue.pauseAll(TIMEOUT_MESSAGE)
            stopSelf()
        }
    }

    companion object {
        const val ACTION_PAUSE_ALL = "io.github.hamzabhai.raftaaro.PAUSE_ALL"
        const val TIMEOUT_MESSAGE = "Paused by Android's background limit — tap to resume"
    }
}
