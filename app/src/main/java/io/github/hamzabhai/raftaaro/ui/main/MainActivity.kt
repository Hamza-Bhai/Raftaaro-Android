package io.github.hamzabhai.raftaaro.ui.main

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.hamzabhai.raftaaro.core.Job
import io.github.hamzabhai.raftaaro.graph
import io.github.hamzabhai.raftaaro.ui.NotificationPermission
import io.github.hamzabhai.raftaaro.ui.settings.SettingsScreen
import io.github.hamzabhai.raftaaro.ui.share.ShareSheetContent
import io.github.hamzabhai.raftaaro.ui.share.ShareViewModel
import io.github.hamzabhai.raftaaro.ui.signin.SignInActivity
import io.github.hamzabhai.raftaaro.ui.theme.RaftaaroTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val share: ShareViewModel by viewModels()
    private val notifications = NotificationPermission(this)
    private val snackbar = SnackbarHostState()
    private val sheetOpen = MutableStateFlow(false)
    private val batteryTip = MutableStateFlow(false)

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch { if (vm.shouldShowBatteryTip()) batteryTip.value = true }
        setContent {
            RaftaaroTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "main") {
                    composable("main") {
                        val jobs by vm.jobs.collectAsState()
                        val link by vm.link.collectAsState()
                        val open by sheetOpen.collectAsState()
                        val tip by batteryTip.collectAsState()
                        var deleting by remember { mutableStateOf<Job?>(null) }
                        MainScreenContent(
                            jobs = jobs, link = link, onLinkChange = { vm.link.value = it },
                            onPaste = vm::pasteFromClipboard,
                            onDownload = { openSheet(link) },
                            onAction = { job, a ->
                                when (a) {
                                    CardAction.OPEN -> start(job.savedUri?.let { graph.saver.open(it) })
                                    CardAction.SHARE -> start(job.savedUri?.let { graph.saver.share(it) })
                                    CardAction.DELETE -> deleting = job
                                    else -> vm.act(job, a)
                                }
                            },
                            onSettings = { nav.navigate("settings") },
                            snackbar = snackbar
                        )
                        if (open) {
                            val state by share.state.collectAsState()
                            ModalBottomSheet(onDismissRequest = { sheetOpen.value = false }) {
                                state?.let { s ->
                                    ShareSheetContent(
                                        s,
                                        onPick = { c -> notifications.askThen { share.pick(c); picked() } },
                                        onPlaylist = { e, c -> notifications.askThen { share.pickPlaylist(e, c); picked() } },
                                        onSignIn = { startActivity(SignInActivity.intent(this@MainActivity, it)) },
                                        onClose = { sheetOpen.value = false }
                                    )
                                }
                            }
                        }
                        deleting?.let { job -> DeleteDialog(job, onDone = { deleting = null }) }
                        if (tip) BatteryTip { batteryTip.value = false; vm.batteryTipShown() }
                    }
                    composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
                }
            }
        }
    }

    /** Android 10+ lets an app read the clipboard only while it has focus. */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) return
        lifecycleScope.launch {
            val url = vm.clipboardOffer() ?: return@launch
            val r = snackbar.showSnackbar("Download this link?", actionLabel = "Download", withDismissAction = true)
            if (r == SnackbarResult.ActionPerformed) {
                vm.link.value = url
                openSheet(url)
            }
        }
    }

    private fun openSheet(text: String) {
        share.load(text)
        sheetOpen.value = true
    }

    private fun picked() {
        sheetOpen.value = false
        vm.link.value = ""
        Toast.makeText(this, "Download started", Toast.LENGTH_SHORT).show()
    }

    private fun start(intent: Intent?) {
        if (intent == null) return
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "No app on this phone can open this file", Toast.LENGTH_SHORT).show()
        }
    }

    @androidx.compose.runtime.Composable
    private fun DeleteDialog(job: Job, onDone: () -> Unit) {
        var alsoFile by remember { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = onDone,
            title = { Text("Delete download?") },
            text = {
                Row(Modifier.clickable { alsoFile = !alsoFile }, verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = alsoFile, onCheckedChange = null)
                    Text("Also delete the file")
                }
            },
            confirmButton = { TextButton(onClick = { vm.act(job, CardAction.DELETE, alsoFile); onDone() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } }
        )
    }

    @androidx.compose.runtime.Composable
    private fun BatteryTip(onDone: () -> Unit) {
        AlertDialog(
            onDismissRequest = onDone,
            text = { Text("Your phone stopped a download in the background. Allow Raftaaro to run without battery restrictions?") },
            confirmButton = {
                TextButton(onClick = {
                    start(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    onDone()
                }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = onDone) { Text("Not now") } }
        )
    }
}
