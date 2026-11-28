package io.github.hamzabhai.raftaaro.ui.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.hamzabhai.raftaaro.core.Job

/** The downloads screen: one list, unfinished jobs on top and finished ones below. Stateless, so it can be tested with fake jobs. */
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
    val shown = remember(jobs) { homeOrder(jobs) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Raftaaro", fontWeight = FontWeight.SemiBold) },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") } }
            )
        },
        snackbarHost = { SnackbarHost(snackbar) }
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item(key = "link") { LinkBox(link, onLinkChange, onPaste, onDownload) }
            if (jobs.isEmpty()) {
                item(key = "empty") { EmptyState() }
            } else {
                item(key = "summary") {
                    Text(
                        summaryLine(jobs), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, start = 4.dp)
                    )
                }
                items(shown, key = { it.id }) { job ->
                    Box(Modifier.animateItem()) { DownloadCard(job) { onAction(job, it) } }
                }
            }
        }
    }
}

@Composable
private fun LinkBox(link: String, onLinkChange: (String) -> Unit, onPaste: () -> Unit, onDownload: () -> Unit) {
    Column {
        OutlinedTextField(
            value = link, onValueChange = onLinkChange, singleLine = true,
            placeholder = { Text("Paste a video or file link") },
            trailingIcon = {
                IconButton(onClick = onPaste) {
                    Icon(Icons.Outlined.ContentPaste, contentDescription = "Paste", tint = MaterialTheme.colorScheme.primary)
                }
            },
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))
        Button(
            onClick = onDownload, enabled = link.isNotBlank(),
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) {
            Icon(Icons.Filled.Download, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text("Download", style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        Modifier.fillMaxWidth().padding(top = 72.dp, start = 24.dp, end = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(88.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Filled.Download, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(20.dp))
        Text("No downloads yet", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(6.dp))
        Text(
            "Share a video to Raftaaro from any app, or paste a link above.",
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
