package io.github.hamzabhai.raftaaro.engine

import android.content.Context
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import io.github.hamzabhai.raftaaro.core.OUTDATED_MESSAGE
import io.github.hamzabhai.raftaaro.core.ProbeResult
import io.github.hamzabhai.raftaaro.core.ProgressTracker
import io.github.hamzabhai.raftaaro.core.VideoChoice
import io.github.hamzabhai.raftaaro.core.VideoProgress
import io.github.hamzabhai.raftaaro.core.YtDlpError
import io.github.hamzabhai.raftaaro.core.classifyYtDlpError
import io.github.hamzabhai.raftaaro.core.downloadArgs
import io.github.hamzabhai.raftaaro.core.parseProbe
import io.github.hamzabhai.raftaaro.core.probeArgs
import io.github.hamzabhai.raftaaro.core.shouldUpdateYtDlp
import io.github.hamzabhai.raftaaro.data.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** yt-dlp, Python and ffmpeg as bundled by youtubedl-android. */
class YtDlpEngine(context: Context) {
    private val ctx = context.applicationContext
    private val initLock = Mutex()
    private val updateLock = Mutex()
    @Volatile private var ready = false

    /** Unpacks the engine on first run (a few seconds). Safe to call often. */
    suspend fun ensureReady() {
        if (ready) return
        initLock.withLock {
            if (ready) return
            withContext(Dispatchers.IO) {
                YoutubeDL.getInstance().init(ctx)
                FFmpeg.getInstance().init(ctx)
            }
            ready = true
        }
    }

    /** Runs yt-dlp; on cancel kills the process (and its ffmpeg). Returns stdout, throws [YtDlpError]. */
    private suspend fun exec(request: YoutubeDLRequest, processId: String?, onLine: (String) -> Unit = {}): String {
        ensureReady()
        updateLock.withLock { } // never run while yt-dlp itself is being replaced
        return withContext(Dispatchers.IO) {
            coroutineScope {
                val watcher = processId?.let { id ->
                    launch {
                        try {
                            awaitCancellation()
                        } finally {
                            YoutubeDL.getInstance().destroyProcessById(id)
                        }
                    }
                }
                try {
                    YoutubeDL.getInstance().execute(request, processId) { _, _, line -> onLine(line) }.out
                } catch (e: YoutubeDL.CanceledException) {
                    coroutineContext.ensureActive()
                    throw YtDlpError("Download stopped")
                } catch (e: YoutubeDLException) {
                    coroutineContext.ensureActive()
                    throw YtDlpError(classifyYtDlpError(e.message.orEmpty()))
                } finally {
                    watcher?.cancel()
                }
            }
        }
    }

    /** What a link is: one video, or a playlist/channel. A channel root opens its Videos tab. */
    suspend fun probe(url: String, cookieFile: File?): ProbeResult {
        val out = exec(YoutubeDLRequest(url).addCommands(probeArgs(url, cookieFile?.absolutePath)), null)
        val r = parseProbe(url, out)
        if (r is ProbeResult.OpenTab) {
            val tabOut = exec(YoutubeDLRequest(r.url).addCommands(probeArgs(r.url, cookieFile?.absolutePath)), null)
            return parseProbe(url, tabOut)
        }
        return r
    }

    /**
     * Downloads into `workDir` (resuming .part files from an earlier run) and returns the finished file.
     * An "out of date" failure updates yt-dlp once and tries again.
     */
    suspend fun download(
        jobId: String,
        url: String,
        choice: VideoChoice?,
        workDir: File,
        cookieFile: File?,
        expected: Long?,
        onProgress: (VideoProgress) -> Unit,
        onMerging: () -> Unit
    ): File {
        workDir.mkdirs()
        return try {
            downloadOnce(jobId, url, choice, workDir, cookieFile, expected, onProgress, onMerging)
        } catch (e: YtDlpError) {
            if (e.message != OUTDATED_MESSAGE) throw e
            updateNow()
            downloadOnce(jobId, url, choice, workDir, cookieFile, expected, onProgress, onMerging)
        }
    }

    private suspend fun downloadOnce(
        jobId: String, url: String, choice: VideoChoice?, workDir: File, cookieFile: File?, expected: Long?,
        onProgress: (VideoProgress) -> Unit, onMerging: () -> Unit
    ): File {
        val tracker = ProgressTracker(expected)
        val req = YoutubeDLRequest(url).addCommands(downloadArgs(url, choice, workDir.absolutePath, cookieFile?.absolutePath))
        exec(req, jobId) { line ->
            val wasMerging = tracker.merging
            tracker.onLine(line) { p -> File(p).takeIf { it.exists() }?.length() }?.let(onProgress)
            if (tracker.merging && !wasMerging) onMerging()
        }
        val file = listOfNotNull(tracker.finalPath, tracker.seenPath).map(::File).firstOrNull { it.isFile }
            ?: workDir.listFiles().orEmpty()
                .filter { it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl") && !Regex("""\.f\d+\.\w+$""").containsMatchIn(it.name) }
                .maxByOrNull { it.lastModified() }
            ?: throw YtDlpError("yt-dlp finished but no file was written")
        onProgress(VideoProgress(file.length(), file.length(), 0))
        return file
    }

    fun cancel(jobId: String) {
        YoutubeDL.getInstance().destroyProcessById(jobId)
    }

    /** Checks for a new yt-dlp at most once a day. Failures are ignored (next start tries again). */
    suspend fun updateIfDue(settings: SettingsStore) {
        if (!shouldUpdateYtDlp(settings.current().lastYtDlpUpdate, System.currentTimeMillis())) return
        runCatching { updateNow() }.onSuccess {
            settings.update { it.copy(lastYtDlpUpdate = System.currentTimeMillis()) }
        }
    }

    suspend fun updateNow(): String {
        ensureReady()
        return updateLock.withLock {
            withContext(Dispatchers.IO) {
                when (YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)) {
                    YoutubeDL.UpdateStatus.DONE -> "yt-dlp updated to ${version()}"
                    YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "yt-dlp is already up to date"
                    null -> "yt-dlp could not be updated"
                }
            }
        }
    }

    /** "2026.11.23" (the library reports "yt-dlp 2026.11.23"). */
    fun version(): String? =
        (YoutubeDL.getInstance().versionName(ctx) ?: YoutubeDL.getInstance().version(ctx))?.removePrefix("yt-dlp")?.trim()
}
