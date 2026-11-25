package io.github.hamzabhai.raftaaro.ui.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.hamzabhai.raftaaro.ui.NotificationPermission
import io.github.hamzabhai.raftaaro.ui.signin.SignInActivity
import io.github.hamzabhai.raftaaro.ui.theme.RaftaaroTheme

/** Opened from any app's Share menu: a bottom sheet over that app, gone after one tap. */
class ShareActivity : ComponentActivity() {
    private val vm: ShareViewModel by viewModels()
    private val notifications = NotificationPermission(this)

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) vm.load(sharedText(intent))
        setContent {
            RaftaaroTheme {
                val state by vm.state.collectAsState()
                ModalBottomSheet(onDismissRequest = ::finish, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
                    state?.let { s ->
                        ShareSheetContent(
                            s,
                            onPick = { choice -> notifications.askThen { vm.pick(choice); done() } },
                            onPlaylist = { entries, choice -> notifications.askThen { vm.pickPlaylist(entries, choice); done() } },
                            onSignIn = { site -> startActivity(SignInActivity.intent(this@ShareActivity, site)) },
                            onClose = ::finish
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.load(sharedText(intent))
    }

    private fun done() {
        Toast.makeText(this, "Downloading in Raftaaro", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun sharedText(intent: Intent?): String = when (intent?.action) {
        Intent.ACTION_SEND -> listOfNotNull(intent.getStringExtra(Intent.EXTRA_TEXT), intent.getStringExtra(Intent.EXTRA_SUBJECT)).joinToString(" ")
        Intent.ACTION_VIEW -> intent.dataString.orEmpty()
        else -> ""
    }
}
