package io.github.hamzabhai.raftaaro.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.hamzabhai.raftaaro.core.Job
import io.github.hamzabhai.raftaaro.core.JobStatus
import io.github.hamzabhai.raftaaro.core.formatBytes
import io.github.hamzabhai.raftaaro.core.formatEta
import io.github.hamzabhai.raftaaro.core.formatSpeed

enum class CardAction { PAUSE, RESUME, CANCEL, RETRY, REMOVE, OPEN, SHARE, DELETE }

/** One line under the title describing where the job stands. */
fun statusText(job: Job, now: Long = System.currentTimeMillis()): String = when (job.status) {
    JobStatus.QUEUED -> job.retryAt?.takeIf { it > now }?.let { "Retrying in ${((it - now) / 60_000) + 1} min — ${job.error ?: ""}".trimEnd(' ', '—') }
        ?: "Waiting"
    JobStatus.PROBING -> "Starting…"
    JobStatus.DOWNLOADING -> listOf(
        "${formatBytes(job.receivedBytes)} of ${formatBytes(job.totalBytes)}",
        if (job.speedBps > 0) formatSpeed(job.speedBps) else "",
        formatEta(job.etaSec)
    ).filter { it.isNotEmpty() }.joinToString(" · ")
    JobStatus.MERGING -> "Finishing…"
    JobStatus.PAUSED -> job.error ?: "Paused · ${formatBytes(job.receivedBytes)} of ${formatBytes(job.totalBytes)}"
    JobStatus.DONE -> formatBytes(job.totalBytes ?: job.receivedBytes)
    JobStatus.ERROR -> job.error ?: "Download failed"
}

private fun actionsFor(status: JobStatus): List<Pair<String, CardAction>> = when (status) {
    JobStatus.QUEUED, JobStatus.PROBING, JobStatus.DOWNLOADING, JobStatus.MERGING -> listOf("Pause" to CardAction.PAUSE, "Cancel" to CardAction.CANCEL)
    JobStatus.PAUSED -> listOf("Resume" to CardAction.RESUME, "Cancel" to CardAction.CANCEL)
    JobStatus.ERROR -> listOf("Retry" to CardAction.RETRY, "Remove" to CardAction.REMOVE)
    JobStatus.DONE -> listOf("Open" to CardAction.OPEN, "Share" to CardAction.SHARE, "Delete" to CardAction.DELETE)
}

@Composable
fun DownloadCard(job: Job, onAction: (CardAction) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (job.thumbnail != null) {
                    AsyncImage(
                        model = job.thumbnail, contentDescription = null, contentScale = ContentScale.Crop,
                        modifier = Modifier.size(80.dp, 45.dp).clip(RoundedCornerShape(6.dp))
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(job.fileName ?: job.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        statusText(job), style = MaterialTheme.typography.bodySmall,
                        color = if (job.status == JobStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            if (job.status != JobStatus.DONE && job.status != JobStatus.ERROR) {
                val total = job.totalBytes
                if (total != null && total > 0 && job.status != JobStatus.MERGING) {
                    LinearProgressIndicator(
                        progress = { (job.receivedBytes.toFloat() / total).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                    )
                } else if (job.status == JobStatus.DOWNLOADING || job.status == JobStatus.MERGING) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                actionsFor(job.status).forEach { (label, a) -> TextButton(onClick = { onAction(a) }) { Text(label) } }
            }
        }
    }
}
