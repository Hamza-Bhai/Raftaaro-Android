package io.github.hamzabhai.raftaaro.engine

import android.content.Context
import android.util.Log
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
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.coroutines.coroutineContext

/** yt-dlp, Python and ffmpeg as bundled by youtubedl-android. */
class YtDlpEngine(context: Context) {
    private val ctx = context.applicationContext
    /** yt-dlp's own cache (YouTube player code and solved challenges). */
    private val cacheDir = File(ctx.cacheDir, "yt-dlp")
    private val initLock = Mutex()
    private companion object {
        /** YouTube's stream addresses last about six hours; stay well inside that. */
        const val INFO_MAX_AGE_MS = 60 * 60 * 1000L

        /** One yt-dlp file per process: serialises updates across every engine instance. */
        val updateLock = Mutex()
        /** Shared by running yt-dlp processes, exclusive while the yt-dlp file is replaced. */
        val binary = ReentrantReadWriteLock()
    }
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
                    // yt-dlp is a zip Python reads lazily: it must not be replaced while any run uses it
                    binary.readLock().lock()
                    try {
                        // the library adds --no-cache-dir otherwise, and yt-dlp would fetch YouTube's
                        // player and solve its challenge again on every run
                        if (!request.hasOption("--cache-dir")) request.addOption("--cache-dir", cacheDir.absolutePath)
                        YoutubeDL.getInstance().execute(request, processId) { _, _, line -> onLine(line) }.out
                    } finally {
                        binary.readLock().unlock()
                    }
                } catch (e: YoutubeDL.CanceledException) {
                    coroutineContext.ensureActive()
                    throw YtDlpError("Download stopped")
                } catch (e: YoutubeDLException) {
                    coroutineContext.ensureActive()
                    Log.w("Raftaaro", "yt-dlp failed: ${e.message?.takeLast(2000)}", e.cause)
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
        // keep what was read, so the download that usually follows does not read the page again
        if (r is ProbeResult.Video) runCatching { infoFile(url).apply { parentFile?.mkdirs() }.writeText(out) }
        return r
    }

    /** Where the share sheet's reading of a link is kept for the download. */
    private fun infoFile(url: String): File {
        val hash = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(cacheDir, "info/$hash.json")
    }

    /** A reading of this link young enough that YouTube's stream addresses still work. */
    private fun freshInfo(url: String): File? =
        infoFile(url).takeIf { it.isFile && System.currentTimeMillis() - it.lastModified() < INFO_MAX_AGE_MS }

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
        freshInfo(url)?.let { info ->
            try {
                return downloadOnce(jobId, url, choice, workDir, cookieFile, expected, onProgress, onMerging, info)
            } catch (e: YtDlpError) {
                // stale or unusable reading: forget it and read the page as usual
                Log.i("Raftaaro", "download from saved info failed (${e.message}); reading the link again")
                info.delete()
            }
        }
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
        onProgress: (VideoProgress) -> Unit, onMerging: () -> Unit, info: File? = null
    ): File {
        val tracker = ProgressTracker(expected)
        val args = downloadArgs(url, choice, workDir.absolutePath, cookieFile?.absolutePath, info?.absolutePath)
        // with --load-info-json the URL must not be given too, or yt-dlp would read the page anyway
        val req = (if (info != null) YoutubeDLRequest(emptyList<String>()) else YoutubeDLRequest(url)).addCommands(args)
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
                // wait for running yt-dlp processes to finish; a long download makes us give up for now
                if (!binary.writeLock().tryLock(30, TimeUnit.SECONDS)) {
                    throw YtDlpError("yt-dlp is busy with downloads — it will update when they finish")
                }
                try {
                    when (YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)) {
                        YoutubeDL.UpdateStatus.DONE -> "yt-dlp updated to ${version()}"
                        YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "yt-dlp is already up to date"
                        null -> "yt-dlp could not be updated"
                    }
                } finally {
                    binary.writeLock().unlock()
                }
            }
        }
    }

    /** "2026.11.23" (the library reports "yt-dlp 2026.11.23"). */
    fun version(): String? =
        (YoutubeDL.getInstance().versionName(ctx) ?: YoutubeDL.getInstance().version(ctx))?.removePrefix("yt-dlp")?.trim()
}
