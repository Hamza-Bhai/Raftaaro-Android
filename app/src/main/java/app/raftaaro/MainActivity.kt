package app.raftaaro

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        takeSharedLink(intent)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { Screen(vm) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeSharedLink(intent)
    }

    /** A link shared from YouTube, Instagram, a browser… lands in the box. */
    private fun takeSharedLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return
        val link = Regex("""https?://\S+""").find(text)?.value ?: return
        vm.url = link
        vm.fetchInfo()
    }

    /** The prototype downloads in the foreground, so keep the screen on while it runs. */
    fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

@Composable
private fun Screen(vm: MainViewModel) {
    val activity = androidx.compose.ui.platform.LocalContext.current as? MainActivity
    LaunchedEffect(vm.downloading) { activity?.keepScreenOn(vm.downloading) }

    Column(
        Modifier
            .safeDrawingPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Raftaaro (prototype)", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            value = vm.url,
            onValueChange = { vm.url = it },
            label = { Text("Video link") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::fetchInfo, enabled = !vm.busy) { Text("Info") }
            Button(onClick = { vm.download(audioOnly = false) }, enabled = !vm.busy) { Text("Video") }
            Button(onClick = { vm.download(audioOnly = true) }, enabled = !vm.busy) { Text("MP3") }
        }

        if (vm.downloading) {
            LinearProgressIndicator(progress = { vm.progress }, modifier = Modifier.fillMaxWidth())
            Text(vm.lastLine, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
        }

        vm.info?.let { i ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(i.title, style = MaterialTheme.typography.titleMedium)
                    i.uploader?.let { Text(it) }
                    Text("Length: ${i.durationSec / 60}:${"%02d".format(i.durationSec % 60)}")
                    if (i.heights.isNotEmpty()) Text("Qualities: " + i.heights.joinToString { "${it}p" })
                }
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Text(vm.status, Modifier.padding(12.dp), fontFamily = FontFamily.Monospace)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = vm::check, enabled = !vm.busy) { Text("Engine check") }
            OutlinedButton(onClick = vm::update, enabled = !vm.busy) { Text("Update yt-dlp") }
        }
    }
}
