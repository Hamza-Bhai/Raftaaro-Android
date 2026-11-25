package io.github.hamzabhai.raftaaro.ui.main

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.hamzabhai.raftaaro.core.Job
import io.github.hamzabhai.raftaaro.core.isDownloadableUrl
import io.github.hamzabhai.raftaaro.graph
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph
    val jobs: StateFlow<List<Job>> = graph.jobs.jobs.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val link = MutableStateFlow("")
    /** The clipboard link already offered, so the same one is not offered twice. */
    private var lastOffered: String? = null

    fun act(job: Job, action: CardAction, deleteFile: Boolean = false) {
        graph.scope.launch {
            when (action) {
                CardAction.PAUSE -> graph.queue.pause(job.id)
                CardAction.RESUME -> graph.queue.resume(job.id)
                CardAction.RETRY -> graph.queue.retry(job.id)
                CardAction.CANCEL, CardAction.REMOVE -> graph.queue.remove(job.id, deleteFile = false)
                CardAction.DELETE -> graph.queue.remove(job.id, deleteFile)
                CardAction.OPEN, CardAction.SHARE -> Unit // handled by the activity (needs an Activity to start)
            }
        }
    }

    /** A copied link worth offering, or null (setting off, not a link, or already offered). */
    suspend fun clipboardOffer(): String? {
        if (!graph.settings.current().clipboardPrompt) return null
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val text = cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(getApplication())?.toString()?.trim()
        if (text == null || !isDownloadableUrl(text) || text == lastOffered || text == link.value) return null
        lastOffered = text
        return text
    }

    fun pasteFromClipboard() {
        val cm = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(getApplication())?.toString()?.trim()?.let { link.value = it }
    }

    /** Show the battery tip once, after the app was killed during a download. */
    suspend fun shouldShowBatteryTip(): Boolean =
        graph.queue.interruptedOnStart.value && !graph.settings.current().batteryTipShown

    fun batteryTipShown() {
        viewModelScope.launch { graph.settings.update { it.copy(batteryTipShown = true) } }
    }
}
