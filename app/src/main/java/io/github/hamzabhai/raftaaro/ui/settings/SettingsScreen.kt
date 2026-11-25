package io.github.hamzabhai.raftaaro.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.hamzabhai.raftaaro.core.Settings
import io.github.hamzabhai.raftaaro.core.Site
import io.github.hamzabhai.raftaaro.download.Bucket
import io.github.hamzabhai.raftaaro.ui.signin.SignInActivity
import kotlin.math.roundToInt

private const val SOURCE_URL = "https://github.com/Hamza-Bhai/Raftaaro-Android"

/** Settings, wired to the stored settings. */
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val ctx = LocalContext.current
    val s by vm.settings.collectAsState()
    val signedIn by vm.signedIn.collectAsState()
    val message by vm.updateMessage.collectAsState()
    var picking by remember { mutableStateOf<Bucket?>(null) }
    LifecycleResumeEffect(Unit) {
        vm.refreshSignIns()
        onPauseOrDispose { }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        val bucket = picking
        if (uri != null && bucket != null) {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            vm.setFolder(bucket, uri.toString())
        }
    }
    s?.let { settings ->
        SettingsContent(
            settings = settings, folderNames = vm.folderNames(settings), ytDlpVersion = vm.ytDlpVersion(), updateMessage = message,
            signedIn = signedIn, appVersion = vm.appVersion(),
            onChange = vm::save, onPickFolder = { picking = it; picker.launch(null) }, onResetFolder = { vm.setFolder(it, null) },
            onUpdate = vm::updateNow, onSignIn = { ctx.startActivity(SignInActivity.intent(ctx, it)) }, onSignOut = vm::signOut,
            onBack = onBack
        )
    }
}

/** The settings screen itself, stateless so it can be tested. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsContent(
    settings: Settings,
    folderNames: Map<Bucket, String>,
    ytDlpVersion: String?,
    updateMessage: String?,
    signedIn: Map<Site, Boolean>,
    appVersion: String,
    onChange: (Settings) -> Unit,
    onPickFolder: (Bucket) -> Unit,
    onResetFolder: (Bucket) -> Unit,
    onUpdate: () -> Unit,
    onSignIn: (Site) -> Unit,
    onSignOut: (Site) -> Unit,
    onBack: () -> Unit
) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") } }
        )
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            Section("Save to")
            Bucket.entries.forEach { b ->
                val picked = when (b) {
                    Bucket.VIDEOS -> settings.videosDir
                    Bucket.AUDIO -> settings.audioDir
                    Bucket.FILES -> settings.filesDir
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(b.folder, style = MaterialTheme.typography.bodyLarge)
                        Text(folderNames[b] ?: "Download/Raftaaro/${b.folder}", style = MaterialTheme.typography.bodySmall)
                    }
                    if (picked != null) TextButton(onClick = { onResetFolder(b) }) { Text("Reset") }
                    TextButton(onClick = { onPickFolder(b) }) { Text("Change") }
                }
            }

            Section("Downloads")
            SliderRow("Downloads at the same time", settings.maxConcurrent, 1..5, "maxConcurrent") { onChange(settings.copy(maxConcurrent = it)) }
            SliderRow("Connections per file", settings.partsPerFile, 1..16, "partsPerFile") { onChange(settings.copy(partsPerFile = it)) }
            SwitchRow("Offer to download copied links", settings.clipboardPrompt) { onChange(settings.copy(clipboardPrompt = it)) }

            Section("Sign-ins")
            Text(
                "Some videos need you to be signed in. Your sign-in stays on this phone.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp)
            )
            SwitchRow("Use my sign-ins for downloads", settings.useSignIns) { onChange(settings.copy(useSignIns = it)) }
            Site.entries.forEach { site ->
                val on = signedIn[site] == true
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(site.label, style = MaterialTheme.typography.bodyLarge)
                        Text(if (on) "Signed in" else "Not signed in", style = MaterialTheme.typography.bodySmall)
                    }
                    if (on) OutlinedButton(onClick = { onSignOut(site) }) { Text("Sign out") }
                    else TextButton(onClick = { onSignIn(site) }) { Text("Sign in") }
                }
            }

            Section("yt-dlp")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("yt-dlp ${ytDlpVersion ?: "…"}", style = MaterialTheme.typography.bodyLarge)
                    Text(updateMessage ?: "Updates itself once a day", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = onUpdate) { Text("Update now") }
            }

            Section("About")
            val uri = LocalUriHandler.current
            Text("Raftaaro $appVersion · free and open source (GPL-3.0)", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Uses yt-dlp (Unlicense), FFmpeg (LGPL 2.1+), Python (PSF), QuickJS (MIT) and youtubedl-android (GPL-3.0).",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp)
            )
            Text("Download only content you have the right to download.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { uri.openUri(SOURCE_URL) }) { Text("Source code") }
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 20.dp, bottom = 4.dp))
    HorizontalDivider(Modifier.padding(bottom = 4.dp))
}

@Composable
private fun SliderRow(label: String, value: Int, range: IntRange, tag: String, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Text("$value", style = MaterialTheme.typography.bodyLarge)
    }
    Slider(
        value = value.toFloat(),
        onValueChange = { v -> v.roundToInt().coerceIn(range).takeIf { it != value }?.let(onChange) },
        valueRange = range.first.toFloat()..range.last.toFloat(),
        steps = range.last - range.first - 1,
        modifier = Modifier.testTag(tag)
    )
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
