package io.github.hamzabhai.raftaaro.ui.main

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import io.github.hamzabhai.raftaaro.core.Job
import io.github.hamzabhai.raftaaro.core.JobKind
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
    JobStatus.PAUSED -> job.error ?: "${formatBytes(job.receivedBytes)} of ${formatBytes(job.totalBytes)}"
    JobStatus.DONE -> formatBytes(job.totalBytes ?: job.receivedBytes)
    JobStatus.ERROR -> job.error ?: "Download failed"
}

private data class CardButton(val label: String, val icon: ImageVector, val action: CardAction, val quiet: Boolean = false)

private fun buttonsFor(job: Job): List<CardButton> = when (job.status) {
    JobStatus.QUEUED, JobStatus.PROBING, JobStatus.DOWNLOADING, JobStatus.MERGING -> listOf(
        CardButton("Pause", Icons.Filled.Pause, CardAction.PAUSE),
        CardButton("Cancel", Icons.Filled.Close, CardAction.CANCEL, quiet = true)
    )
    JobStatus.PAUSED -> listOf(
        CardButton("Resume", Icons.Filled.PlayArrow, CardAction.RESUME),
        CardButton("Cancel", Icons.Filled.Close, CardAction.CANCEL, quiet = true)
    )
    JobStatus.ERROR -> listOf(
        CardButton("Retry", Icons.Filled.Refresh, CardAction.RETRY),
        CardButton("Remove", Icons.Filled.Delete, CardAction.REMOVE, quiet = true)
    )
    JobStatus.DONE -> listOf(
        CardButton("Open", if (job.kind == JobKind.VIDEO) Icons.Filled.PlayArrow else Icons.AutoMirrored.Filled.OpenInNew, CardAction.OPEN),
        CardButton("Share", Icons.Filled.Share, CardAction.SHARE),
        CardButton("Delete", Icons.Filled.Delete, CardAction.DELETE, quiet = true)
    )
}

/** Background and text colour of a status pill; the progress bar takes the text colour. */
private data class Tone(val bg: Color, val fg: Color)

@Composable
private fun toneFor(status: JobStatus): Tone {
    val cs = MaterialTheme.colorScheme
    val dark = cs.surface.luminance() < 0.5f
    return when (status) {
        JobStatus.PAUSED -> if (dark) Tone(Color(0xFF4A3800), Color(0xFFFFDF9E)) else Tone(Color(0xFFFFEFC2), Color(0xFF6B4E00))
        JobStatus.ERROR -> Tone(cs.errorContainer, cs.onErrorContainer)
        JobStatus.DONE -> if (dark) Tone(Color(0xFF1E4426), Color(0xFFA6E3B4)) else Tone(Color(0xFFD5F2DC), Color(0xFF1B5E2B))
        JobStatus.QUEUED, JobStatus.PROBING -> Tone(cs.surfaceVariant, cs.onSurfaceVariant)
        JobStatus.DOWNLOADING, JobStatus.MERGING -> Tone(cs.primaryContainer, cs.onPrimaryContainer)
    }
}

@Composable
fun DownloadCard(job: Job, onAction: (CardAction) -> Unit) {
    val failed = job.status == JobStatus.ERROR
    val content: @Composable () -> Unit = { CardBody(job, onAction) }
    if (failed) {
        OutlinedCard(
            Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f))
        ) { content() }
    } else {
        ElevatedCard(
            Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
        ) { content() }
    }
}

@Composable
private fun CardBody(job: Job, onAction: (CardAction) -> Unit) {
    val tone = toneFor(job.status)
    val done = job.status == JobStatus.DONE
    Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 14.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(end = 8.dp)) {
            Thumb(job)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    job.fileName ?: job.title, style = MaterialTheme.typography.titleSmall,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        statusLabel(job), style = MaterialTheme.typography.labelMedium, color = tone.fg,
                        modifier = Modifier.clip(CircleShape).background(tone.bg).padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                    if (done) {
                        Spacer(Modifier.width(8.dp))
                        Text(statusText(job), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        val running = job.status in setOf(JobStatus.QUEUED, JobStatus.PROBING, JobStatus.DOWNLOADING, JobStatus.MERGING, JobStatus.PAUSED)
        if (running) {
            val pct = percentOf(job)
            val barMod = Modifier.fillMaxWidth().padding(top = 12.dp, end = 8.dp).height(6.dp).clip(CircleShape)
            val track = MaterialTheme.colorScheme.surfaceVariant
            if (pct != null && job.status != JobStatus.MERGING) {
                LinearProgressIndicator(
                    progress = { pct / 100f }, modifier = barMod, color = tone.fg, trackColor = track,
                    strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {}
                )
            } else if (job.status == JobStatus.DOWNLOADING || job.status == JobStatus.MERGING || job.status == JobStatus.PROBING) {
                LinearProgressIndicator(barMod, color = tone.fg, trackColor = track, strokeCap = StrokeCap.Round, gapSize = 0.dp)
            }
        }
        if (!done) Text(
            statusText(job), style = MaterialTheme.typography.bodySmall,
            color = if (job.status == JobStatus.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp, end = 8.dp)
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            buttonsFor(job).forEach { b ->
                TextButton(onClick = { onAction(b.action) }, contentPadding = PaddingValues(horizontal = 10.dp)) {
                    val c = if (b.quiet) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary
                    Icon(b.icon, contentDescription = null, tint = c, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(b.label, color = c)
                }
            }
        }
    }
}

@Composable
private fun Thumb(job: Job) {
    val mod = Modifier.size(88.dp, 50.dp).clip(RoundedCornerShape(8.dp))
    if (job.thumbnail != null) {
        AsyncImage(model = job.thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = mod)
    } else {
        Box(mod.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
            Icon(
                if (job.kind == JobKind.VIDEO) Icons.Outlined.Movie else Icons.AutoMirrored.Outlined.InsertDriveFile,
                contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
