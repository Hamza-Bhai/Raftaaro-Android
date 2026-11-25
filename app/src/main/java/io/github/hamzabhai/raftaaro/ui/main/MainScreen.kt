package io.github.hamzabhai.raftaaro.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.hamzabhai.raftaaro.core.Job
import io.github.hamzabhai.raftaaro.core.JobStatus

/** The downloads screen. Stateless apart from the Active/Finished filter, so it can be tested with fake jobs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreenContent(
    jobs: List<Job>,
    link: String,
    onLinkChange: (String) -> Unit,
    onPaste: () -> Unit,
    onDownload: () -> Unit,
    onAction: (Job, CardAction) -> Unit,
    onSettings: () -> Unit,
    snackbar: SnackbarHostState = remember { SnackbarHostState() }
) {
    var finished by rememberSaveable { mutableStateOf(false) }
    val shown = jobs.filter { (it.status == JobStatus.DONE) == finished }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Raftaaro") },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") } }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                OutlinedTextField(
                    value = link, onValueChange = onLinkChange, singleLine = true,
                    label = { Text("Paste a video or file link") }, modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = onPaste) { Text("Paste") }
                    Button(onClick = onDownload, enabled = link.isNotBlank()) { Text("Download") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    FilterChip(selected = !finished, onClick = { finished = false }, label = { Text("Active") })
                    FilterChip(selected = finished, onClick = { finished = true }, label = { Text("Finished") })
                }
            }
            if (jobs.isEmpty()) item {
                Text(
                    "Share a video to Raftaaro from any app, or paste a link above.",
                    style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp)
                )
            } else if (shown.isEmpty()) item {
                Text(
                    if (finished) "Nothing finished yet." else "Nothing downloading right now.",
                    style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 32.dp)
                )
            }
            items(shown, key = { it.id }) { job -> DownloadCard(job) { onAction(job, it) } }
        }
    }
}
