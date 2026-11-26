package io.github.hamzabhai.raftaaro.ui.share

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.hamzabhai.raftaaro.core.JobKind
import io.github.hamzabhai.raftaaro.core.MediaType
import io.github.hamzabhai.raftaaro.core.PlaylistEntry
import io.github.hamzabhai.raftaaro.core.PlaylistInfo
import io.github.hamzabhai.raftaaro.core.ProbeResult
import io.github.hamzabhai.raftaaro.core.QualityOption
import io.github.hamzabhai.raftaaro.core.SIGN_IN_MESSAGE
import io.github.hamzabhai.raftaaro.core.Site
import io.github.hamzabhai.raftaaro.core.VideoChoice
import io.github.hamzabhai.raftaaro.core.VideoInfo
import io.github.hamzabhai.raftaaro.core.YtDlpError
import io.github.hamzabhai.raftaaro.core.buildQualityOptions
import io.github.hamzabhai.raftaaro.core.presetQualityOptions
import io.github.hamzabhai.raftaaro.core.extractFirstUrl
import io.github.hamzabhai.raftaaro.core.isVideoSiteUrl
import io.github.hamzabhai.raftaaro.core.isYouTubeUrl
import io.github.hamzabhai.raftaaro.core.sanitizeFileName
import io.github.hamzabhai.raftaaro.core.usesSiteLogin
import io.github.hamzabhai.raftaaro.graph
import io.github.hamzabhai.raftaaro.http.inspectFile
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.net.URI

sealed interface ShareState {
    data class Loading(val url: String) : ShareState
    data class NoLink(val text: String) : ShareState
    /** `refining`: a first look with preset qualities while the real list is still being read. */
    data class Video(val info: VideoInfo, val video: List<QualityOption>, val audio: List<QualityOption>, val refining: Boolean = false) : ShareState
    data class Playlist(val info: PlaylistInfo) : ShareState
    data class File(val url: String, val name: String, val size: Long?) : ShareState
    data class Failed(val message: String, val signIn: Site?) : ShareState
}

private val FILE_EXTENSIONS = setOf(
    "zip", "rar", "7z", "apk", "pdf", "exe", "msi", "dmg", "iso", "mp3", "mp4", "mkv",
    "jpg", "png", "gif", "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx"
)

/** A plain file link (not a video page) judged by its path's extension. */
fun looksLikeFile(url: String): Boolean = runCatching {
    URI(url).path.substringAfterLast('/').substringAfterLast('.', "").lowercase() in FILE_EXTENSIONS
}.getOrDefault(false)

/** Reads a shared or pasted link and enqueues what the user picks. */
class ShareViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph
    private val _state = MutableStateFlow<ShareState?>(null)
    val state: StateFlow<ShareState?> = _state
    private var loading: Job? = null

    fun load(text: String) {
        val url = extractFirstUrl(text)
        if (url == null) {
            _state.value = ShareState.NoLink(text)
            return
        }
        _state.value = ShareState.Loading(url)
        loading?.cancel()
        loading = viewModelScope.launch {
            _state.value = try {
                if (isVideoSiteUrl(url)) probe(url)
                else {
                    // unknown site: a direct file (any type but a web page) skips yt-dlp, which would
                    // otherwise start fetching the whole file just to look at it
                    val inspected = runCatching { inspectFile(graph.http, url) }
                    val info = inspected.getOrNull()
                    when {
                        info != null && (info.isFile || looksLikeFile(url)) -> ShareState.File(url, info.name, info.size)
                        // a file link the server refuses: say so instead of handing it to yt-dlp
                        info == null && looksLikeFile(url) -> failed(url, inspected.exceptionOrNull()!!)
                        else -> probe(url)
                    }
                }
            } catch (e: YtDlpError) {
                if (e.message?.contains("Unsupported URL") == true) runCatching { fileState(url) }.getOrElse { failed(url, it) }
                else failed(url, e)
            } catch (e: Exception) {
                failed(url, e)
            }
        }
    }

    private fun failed(url: String, e: Throwable) = ShareState.Failed(
        e.message ?: "Could not read this link",
        if (e.message == SIGN_IN_MESSAGE) usesSiteLogin(url) else null
    )

    private suspend fun fileState(url: String): ShareState {
        val info = inspectFile(graph.http, url)
        return ShareState.File(url, info.name, info.size)
    }

    private suspend fun probe(url: String): ShareState = coroutineScope {
        val t0 = System.currentTimeMillis()
        val settings = graph.settings.current()
        graph.engine.ensureReady()
        val cookies = graph.cookiesFor(url, settings)
        // Listing every quality takes a while on a phone; a quick first look (no JavaScript to solve)
        // runs beside it and fills the sheet meanwhile. With the solver's player cached and yt-dlp
        // precompiled, both together fit the phone's CPU and memory.
        val full = async {
            if (isYouTubeUrl(url)) graph.awaitFirstUpdate()
            graph.engine.probe(url, cookies)
        }
        val quick = if (isYouTubeVideo(url)) runCatching { graph.engine.quickProbe(url, cookies) }.getOrNull() as? ProbeResult.Video else null
        if (quick != null && !full.isCompleted) {
            val (video, audio) = presetQualityOptions()
            _state.value = ShareState.Video(quick.info, video, audio, refining = true)
            Log.i("Raftaaro", "probe timing: first look after ${System.currentTimeMillis() - t0} ms")
        }
        val r = try {
            full.await()
        } catch (e: YtDlpError) {
            // the first look still works: downloads read the link themselves
            if (quick != null) return@coroutineScope ShareState.Video(quick.info, presetQualityOptions().first, presetQualityOptions().second)
            throw e
        }
        Log.i("Raftaaro", "probe timing: full list after ${System.currentTimeMillis() - t0} ms")
        when (r) {
            is ProbeResult.Video -> ShareState.Video(r.info, buildQualityOptions(r.info, MediaType.VIDEO), buildQualityOptions(r.info, MediaType.AUDIO))
            is ProbeResult.Playlist -> ShareState.Playlist(r.info)
            is ProbeResult.OpenTab -> ShareState.Failed("Could not open this channel", null)
        }
    }

    /** A single YouTube video (not a playlist or channel), the only kind the quick first look handles. */
    private fun isYouTubeVideo(url: String): Boolean =
        isYouTubeUrl(url) && (Regex("""[?&]v=""").containsMatchIn(url) || "youtu.be/" in url || "/shorts/" in url)

    /** Enqueue the single video or file shown. `choice == null` means a plain file. Runs past the sheet closing. */
    fun pick(choice: VideoChoice?) {
        when (val s = _state.value) {
            is ShareState.Video -> {
                val est = (s.video + s.audio).firstOrNull {
                    if (choice?.media == MediaType.AUDIO) it.audioFormat == choice.audioFormat else it.audioFormat == null && it.height == choice?.height
                }?.estBytes
                graph.scope.launch { graph.queue.enqueue(s.info.url, s.info.title, JobKind.VIDEO, choice, null, s.info.thumbnail, est) }
            }
            is ShareState.File -> graph.scope.launch { graph.queue.enqueue(s.url, s.name, JobKind.FILE, null, null, null, s.size) }
            else -> Unit
        }
    }

    /** Enqueue the ticked playlist entries into a folder named after the playlist. */
    fun pickPlaylist(entries: List<PlaylistEntry>, choice: VideoChoice) {
        val s = _state.value as? ShareState.Playlist ?: return
        val folder = sanitizeFileName(s.info.title)
        graph.scope.launch {
            entries.forEach { graph.queue.enqueue(it.url, it.title, JobKind.VIDEO, choice, folder, null) }
        }
    }
}
