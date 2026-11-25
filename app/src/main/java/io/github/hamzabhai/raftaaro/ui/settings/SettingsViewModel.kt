package io.github.hamzabhai.raftaaro.ui.settings

import android.app.Application
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.hamzabhai.raftaaro.core.Settings
import io.github.hamzabhai.raftaaro.core.Site
import io.github.hamzabhai.raftaaro.download.Bucket
import io.github.hamzabhai.raftaaro.graph
import io.github.hamzabhai.raftaaro.ui.signin.SignIns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph
    val settings: StateFlow<Settings?> = graph.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val signedIn = MutableStateFlow(readSignIns())
    val updateMessage = MutableStateFlow<String?>(null)

    private fun readSignIns() = Site.entries.associateWith { SignIns.isSignedIn(graph, it) }

    /** Sign-in state changes in SignInActivity; refresh when the screen comes back. */
    fun refreshSignIns() {
        signedIn.value = readSignIns()
    }

    fun save(s: Settings) {
        viewModelScope.launch { graph.settings.update { s } }
    }

    fun setFolder(bucket: Bucket, uri: String?) {
        viewModelScope.launch {
            graph.settings.update {
                when (bucket) {
                    Bucket.VIDEOS -> it.copy(videosDir = uri)
                    Bucket.AUDIO -> it.copy(audioDir = uri)
                    Bucket.FILES -> it.copy(filesDir = uri)
                }
            }
        }
    }

    /** Names of folders picked with the system picker. */
    fun folderNames(s: Settings): Map<Bucket, String> = buildMap {
        listOf(Bucket.VIDEOS to s.videosDir, Bucket.AUDIO to s.audioDir, Bucket.FILES to s.filesDir).forEach { (b, uri) ->
            uri?.let { DocumentFile.fromTreeUri(getApplication(), Uri.parse(it))?.name }?.let { put(b, it) }
        }
    }

    fun updateNow() {
        updateMessage.value = "Checking…"
        viewModelScope.launch {
            updateMessage.value = runCatching { graph.engine.updateNow() }.getOrElse { "Update failed: ${it.message}" }
            graph.settings.update { it.copy(lastYtDlpUpdate = System.currentTimeMillis()) }
        }
    }

    fun ytDlpVersion(): String? = runCatching { graph.engine.version() }.getOrNull()

    fun appVersion(): String = getApplication<Application>().let {
        it.packageManager.getPackageInfo(it.packageName, 0).versionName ?: ""
    }

    fun signOut(site: Site) {
        SignIns.signOut(graph, site)
        refreshSignIns()
    }
}
