package app.raftaaro

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(app: Application) : AndroidViewModel(app) {
    var url by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var downloading by mutableStateOf(false)
        private set
    var progress by mutableStateOf(0f)
        private set
    var status by mutableStateOf("Starting engine…")
        private set
    var info by mutableStateOf<LinkInfo?>(null)
        private set
    var lastLine by mutableStateOf("")
        private set

    private var job: Job? = null

    init {
        run("Engine ready") { Engine.init(it); null }
    }

    fun check() = run("Checking engine…") { Engine.check(it) }

    fun update() = run("Updating yt-dlp…") { Engine.update(it) }

    fun fetchInfo() {
        val link = url.trim()
        if (link.isEmpty()) return
        info = null
        run("Reading link…") {
            val i = Engine.info(it, link)
            withContext(Dispatchers.Main) { info = i }
            "Found: ${i.title}"
        }
    }

    fun download(audioOnly: Boolean) {
        val link = url.trim()
        if (link.isEmpty()) return
        progress = 0f
        downloading = true
        run("Downloading…") { ctx ->
            try {
                val saved = Engine.download(ctx, link, audioOnly) { p, eta, line ->
                    viewModelScope.launch {
                        if (p >= 0) progress = p / 100f
                        lastLine = if (eta > 0) "$line  (ETA ${eta}s)" else line
                    }
                }
                "Saved to Download/Raftaaro:\n" + saved.joinToString("\n")
            } finally {
                withContext(Dispatchers.Main) { downloading = false }
            }
        }
    }

    fun cancel() {
        Engine.cancel()
        job?.cancel()
    }

    /** Runs engine work off the main thread and shows the result, or the error yt-dlp gave. */
    private fun run(label: String, work: suspend (Application) -> String?) {
        if (busy) return
        busy = true
        status = label
        job = viewModelScope.launch {
            status = try {
                withContext(Dispatchers.IO) { work(getApplication()) } ?: label
            } catch (e: Throwable) {
                "Error: " + (e.message?.lines()?.lastOrNull { it.isNotBlank() } ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }
}
