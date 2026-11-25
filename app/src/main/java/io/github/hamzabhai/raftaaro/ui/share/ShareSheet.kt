package io.github.hamzabhai.raftaaro.ui.share

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.hamzabhai.raftaaro.core.AudioFormat
import io.github.hamzabhai.raftaaro.core.MediaType
import io.github.hamzabhai.raftaaro.core.PlaylistEntry
import io.github.hamzabhai.raftaaro.core.QualityOption
import io.github.hamzabhai.raftaaro.core.Site
import io.github.hamzabhai.raftaaro.core.VideoChoice
import io.github.hamzabhai.raftaaro.core.formatBytes
import io.github.hamzabhai.raftaaro.core.formatDuration

/** What the share sheet (and the in-app link box) shows for one link. Stateless, so it can be tested. */
@Composable
fun ShareSheetContent(
    state: ShareState,
    onPick: (VideoChoice?) -> Unit,
    onPlaylist: (List<PlaylistEntry>, VideoChoice) -> Unit,
    onSignIn: (Site) -> Unit,
    onClose: () -> Unit
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
        when (state) {
            is ShareState.Loading -> Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 24.dp)) {
                CircularProgressIndicator(Modifier.size(28.dp))
                Spacer(Modifier.width(16.dp))
                Column {
                    Text("Reading link…", style = MaterialTheme.typography.titleMedium)
                    Text(state.url, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            is ShareState.NoLink -> Message("No link found in what you shared.", onClose)
            is ShareState.Failed -> {
                Text(state.message, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.signIn?.let { site -> Button(onClick = { onSignIn(site) }) { Text("Sign in to ${site.label}") } }
                    OutlinedButton(onClick = onClose) { Text("Close") }
                }
            }
            is ShareState.File -> {
                Text(state.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                Text(formatBytes(state.size).let { if (it == "?") "Size unknown" else it }, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(16.dp))
                Button(onClick = { onPick(null) }, modifier = Modifier.fillMaxWidth()) { Text("Download") }
            }
            is ShareState.Video -> VideoChoices(state, onPick)
            is ShareState.Playlist -> PlaylistChoices(state, onPlaylist)
        }
    }
}

@Composable
private fun Message(text: String, onClose: () -> Unit) {
    Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 16.dp))
    OutlinedButton(onClick = onClose) { Text("Close") }
}

@Composable
private fun VideoChoices(state: ShareState.Video, onPick: (VideoChoice?) -> Unit) {
    val info = state.info
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        if (info.thumbnail != null) {
            AsyncImage(
                model = info.thumbnail, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.size(112.dp, 63.dp).clip(RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(12.dp))
        }
        Column {
            Text(info.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(info.uploader, formatDuration(info.durationSec).ifEmpty { null }).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall)
        }
    }
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Section("Video")
        state.video.forEach { o -> QualityRow(o) { onPick(VideoChoice(MediaType.VIDEO, o.height)) } }
        Section("Audio")
        state.audio.forEach { o -> QualityRow(o) { onPick(VideoChoice(MediaType.AUDIO, null, o.audioFormat)) } }
        if (state.refining) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 12.dp)) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(10.dp))
            Text("Checking all qualities…", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    HorizontalDivider()
}

@Composable
private fun QualityRow(o: QualityOption, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().testTag("quality").clickable(onClick = onClick).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(o.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        o.estBytes?.let { Text("~${formatBytes(it)}", style = MaterialTheme.typography.bodyMedium) }
    }
}

/** Qualities for a whole playlist; heights are maximums because entries are not probed one by one. */
private val PLAYLIST_CHOICES = listOf(
    "Best" to VideoChoice(MediaType.VIDEO, null),
    "1080p" to VideoChoice(MediaType.VIDEO, 1080),
    "720p" to VideoChoice(MediaType.VIDEO, 720),
    "480p" to VideoChoice(MediaType.VIDEO, 480),
    "MP3" to VideoChoice(MediaType.AUDIO, null, AudioFormat.MP3),
    "M4A" to VideoChoice(MediaType.AUDIO, null, AudioFormat.M4A)
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.PlaylistChoices(state: ShareState.Playlist, onPlaylist: (List<PlaylistEntry>, VideoChoice) -> Unit) {
    val entries = state.info.entries
    var ticked by remember(entries) { mutableStateOf(entries.indices.toSet()) }
    var choice by remember { mutableStateOf(PLAYLIST_CHOICES.first().second) }
    Text(state.info.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    Text("${entries.size} videos", style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
        PLAYLIST_CHOICES.forEach { (label, c) ->
            FilterChip(selected = choice == c, onClick = { choice = c }, label = { Text(label) })
        }
    }
    Row(
        Modifier.fillMaxWidth().clickable { ticked = if (ticked.size == entries.size) emptySet() else entries.indices.toSet() },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = ticked.size == entries.size, onCheckedChange = null)
        Text("Select all", modifier = Modifier.padding(start = 8.dp))
    }
    HorizontalDivider()
    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
        entries.forEachIndexed { i, e ->
            Row(
                Modifier.fillMaxWidth().clickable { ticked = if (i in ticked) ticked - i else ticked + i }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = i in ticked, onCheckedChange = null)
                Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
                Text(formatDuration(e.durationSec), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
    Button(
        onClick = { onPlaylist(entries.filterIndexed { i, _ -> i in ticked }, choice) },
        enabled = ticked.isNotEmpty(),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
    ) { Text("Download ${ticked.size}") }
}
