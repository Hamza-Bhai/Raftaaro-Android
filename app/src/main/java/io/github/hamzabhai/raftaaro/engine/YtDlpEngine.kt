package io.github.hamzabhai.raftaaro.engine

import android.content.Context
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import io.github.hamzabhai.raftaaro.BuildConfig
import io.github.hamzabhai.raftaaro.core.OUTDATED_MESSAGE
import io.github.hamzabhai.raftaaro.core.ProbeResult
import io.github.hamzabhai.raftaaro.core.ProgressTracker
import io.github.hamzabhai.raftaaro.core.VideoChoice
import io.github.hamzabhai.raftaaro.core.VideoProgress
import io.github.hamzabhai.raftaaro.core.YtDlpError
import io.github.hamzabhai.raftaaro.core.classifyYtDlpError
import io.github.hamzabhai.raftaaro.core.downloadArgs
import io.github.hamzabhai.raftaaro.core.parseProbe
import io.github.hamzabhai.raftaaro.core.planDirect
import io.github.hamzabhai.raftaaro.core.PLAYER_CACHE_PLUGIN
import io.github.hamzabhai.raftaaro.core.PRECOMPILE_SCRIPT
import io.github.hamzabhai.raftaaro.core.WARM_UP_URL
import io.github.hamzabhai.raftaaro.core.RATE_LIMIT_MESSAGE
import io.github.hamzabhai.raftaaro.core.LIVE_MESSAGE
import io.github.hamzabhai.raftaaro.core.isYouTubeUrl
import io.github.hamzabhai.raftaaro.core.nextReadAt
import io.github.hamzabhai.raftaaro.core.stalePlayerFiles
import io.github.hamzabhai.raftaaro.core.probeArgs
import io.github.hamzabhai.raftaaro.core.shouldUpdateYtDlp
import io.github.hamzabhai.raftaaro.data.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.coroutines.coroutineContext
import kotlin.random.Random

/** yt-dlp, Python and ffmpeg as bundled by youtubedl-android. */
class YtDlpEngine(
    context: Context,
    /** Runs link readings that must outlive whoever asked (the share sheet closes on a tap). */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** With a client, downloads of an already-read link fetch the streams directly (no Python). */
    http: OkHttpClient? = null
) {
    private val ctx = context.applicationContext
    private val bundled = Bundled(ctx)
    private val direct = http?.let { DirectDownload(it, Ffmpeg(bundled)) }
    /** yt-dlp plugin folder (see [PLAYER_CACHE_PLUGIN]). */
    private val pluginDir = File(ctx.filesDir, "ytdlp-plugins")
    private val pluginFile = File(pluginDir, "raftaaro/yt_dlp_plugins/extractor/raftaaro_player_cache.py")
    /** "size:modified" of the yt-dlp file once it was precompiled; differs after the library replaces it. */
    private val compiledMark = File(bundled.ytDlp.parentFile, "yt-dlp.compiled")
    /** Link readings under way, by URL. */
    private val probes = ConcurrentHashMap<String, Deferred<ProbeResult>>()
    /** yt-dlp's own cache (YouTube player code and solved challenges). */
    private val cacheDir = File(ctx.cacheDir, "yt-dlp")
    private val initLock = Mutex()
    /** Paced (playlist) YouTube page reads go one at a time, a few seconds apart. */
    private val readPace = Mutex()
    @Volatile private var lastPacedRead: Long? = null
    private companion object {
        /** YouTube's stream addresses last about six hours; stay well inside that. */
        const val INFO_MAX_AGE_MS = 60 * 60 * 1000L
        /** Pause between paced page reads, as yt-dlp's --sleep-interval 3 --max-sleep-interval 8 did. */
        const val MIN_READ_GAP_MS = 3_000L
        const val MAX_READ_GAP_MS = 8_000L

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
                if (!pluginFile.isFile || pluginFile.readText() != PLAYER_CACHE_PLUGIN) {
                    pluginFile.parentFile?.mkdirs()
                    pluginFile.writeText(PLAYER_CACHE_PLUGIN)
                }
            }
            ready = true
        }
    }

    private fun ytDlpStamp() = bundled.ytDlp.let { "${it.length()}:${it.lastModified()}" }

    /**
     * Adds compiled code to the yt-dlp zip (once per yt-dlp version) so Python starts it in ~1 s
     * instead of ~4 s. Takes a few seconds; skipped while yt-dlp runs, and tried again next start.
     * A copy that fails to start is never put in place.
     */
    suspend fun precompileIfNeeded() {
        ensureReady()
        fun compiled() = compiledMark.isFile && compiledMark.readText() == ytDlpStamp()
        if (compiled()) return
        updateLock.withLock {
            // another caller (start-up, the daily warm-up) may have compiled it while we waited
            if (compiled()) return
            val src = bundled.ytDlp
            val tmp = File(src.parentFile, "yt-dlp.compiling")
            val script = File(ctx.cacheDir, "precompile.py").apply { writeText(PRECOMPILE_SCRIPT) }
            try {
                val t0 = System.currentTimeMillis()
                // updateLock keeps the source from being replaced while it is read
                bundled.run("python", listOf(script.absolutePath, src.absolutePath, tmp.absolutePath))
                if (bundled.run("python", listOf(tmp.absolutePath, "--version")).isBlank()) throw IOException("compiled yt-dlp did not start")
                withContext(Dispatchers.IO) {
                    if (!binary.writeLock().tryLock(5, TimeUnit.SECONDS)) throw IOException("yt-dlp is busy")
                    try {
                        if (!tmp.renameTo(src)) throw IOException("could not replace yt-dlp")
                        compiledMark.writeText(ytDlpStamp())
                    } finally {
                        binary.writeLock().unlock()
                    }
                }
                Log.i("Raftaaro", "yt-dlp precompiled in ${System.currentTimeMillis() - t0} ms")
            } catch (e: IOException) {
                Log.i("Raftaaro", "yt-dlp not precompiled: ${e.message}")
            } finally {
                tmp.delete()
                script.delete()
            }
        }
    }

    /** Deletes all but the newest prepared YouTube players the challenge solver kept. */
    private fun dropStalePlayers() = runCatching {
        val dir = File(cacheDir, "challenge-solver")
        val files = dir.listFiles().orEmpty().map { it.name to it.lastModified() }
        stalePlayerFiles(files, keep = 2).forEach { File(dir, it).delete() }
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
                        // keeps YouTube's prepared player between runs (PLAYER_CACHE_PLUGIN)
                        if (!request.hasOption("--plugin-dirs")) request.addOption("--plugin-dirs", pluginDir.absolutePath)
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

    /**
     * What a link is: one video, or a playlist/channel. A channel root opens its Videos tab.
     * The reading outlives the share sheet closing, and a download of the same link waits for it
     * instead of reading the page a second time (two at once double the time on a phone, and the
     * phone may kill the app for the memory they take).
     */
    suspend fun probe(url: String, cookieFile: File?): ProbeResult {
        val mine = scope.async(start = CoroutineStart.LAZY) { readLink(url, cookieFile) }
        mine.invokeOnCompletion { probes.remove(url, mine) }
        val running = probes.putIfAbsent(url, mine)
        if (running != null) mine.cancel() else mine.start()
        return (running ?: mine).await()
    }

    /**
     * Reads [WARM_UP_URL] so yt-dlp's cache holds YouTube's current player with its challenge
     * solved (and old players are deleted). Returns how long it took. Throws [YtDlpError].
     */
    suspend fun warmPlayerCache(): Long {
        val t0 = System.currentTimeMillis()
        probe(WARM_UP_URL, null)
        return System.currentTimeMillis() - t0
    }

    /** Lets a reading of this link that is under way finish, so its result can be used. */
    private suspend fun awaitProbe(url: String) {
        val running = probes[url] ?: return
        try {
            running.await()
        } catch (e: Exception) {
            coroutineContext.ensureActive() // its failure is not ours: the download reads the link itself
        }
    }

    private suspend fun readLink(url: String, cookieFile: File?): ProbeResult {
        val out = exec(YoutubeDLRequest(url).addCommands(probeArgs(url, cookieFile?.absolutePath)), null)
        dropStalePlayers()
        val r = parseProbe(url, out)
        if (r is ProbeResult.OpenTab) {
            val tabOut = exec(YoutubeDLRequest(r.url).addCommands(probeArgs(r.url, cookieFile?.absolutePath)), null)
            return parseProbe(url, tabOut)
        }
        // keep what was read, so the download that usually follows does not read the page again
        if (r is ProbeResult.Video) runCatching { infoFile(url).apply { parentFile?.mkdirs() }.writeText(out) }
        return r
    }

    /**
     * Reads a playlist video's page so it can be fetched directly: after the pause since the last
     * paced read, and not at all when a fresh reading is already kept.
     */
    private suspend fun pacedRead(url: String, cookieFile: File?) {
        readPace.withLock {
            if (freshInfo(url) != null) return
            val gap = Random.nextLong(MIN_READ_GAP_MS, MAX_READ_GAP_MS + 1)
            delay(nextReadAt(lastPacedRead, System.currentTimeMillis(), gap) - System.currentTimeMillis())
            try {
                probe(url, cookieFile)
            } finally {
                lastPacedRead = System.currentTimeMillis()
            }
        }
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
     * An "out of date" failure updates yt-dlp once and tries again. `paced`: part of a batch (a playlist),
     * so YouTube downloads pause between requests; a single video starts at once. `readAhead`: the next
     * playlist video, whose page is read while this one downloads. `target`: where a direct
     * download may write its file in its final place (the returned file is then that one).
     */
    suspend fun download(
        jobId: String,
        url: String,
        choice: VideoChoice?,
        workDir: File,
        cookieFile: File?,
        expected: Long?,
        onProgress: (VideoProgress) -> Unit,
        onMerging: () -> Unit,
        paced: Boolean = false,
        parts: Int = 8,
        readAhead: String? = null,
        target: SaveTarget? = null
    ): File {
        workDir.mkdirs()
        // tapped while the share sheet was still reading the link: use that reading when it is done
        awaitProbe(url)
        val once: suspend (File?) -> File = { info ->
            downloadOnce(jobId, url, choice, workDir, cookieFile, expected, onProgress, onMerging, info, paced)
        }
        // a playlist video: read its page (paced) so its streams can be fetched directly too
        if (paced && direct != null && isYouTubeUrl(url) && freshInfo(url) == null) {
            try {
                pacedRead(url, cookieFile)
            } catch (e: YtDlpError) {
                // a cooldown or a live stream: yt-dlp would fail the same way
                if (e.message == RATE_LIMIT_MESSAGE || e.message == LIVE_MESSAGE) throw e
                Log.i("Raftaaro", "playlist video not read (${e.message}); handing it to yt-dlp")
            }
        }
        val info = freshInfo(url)
        // read the next playlist video while this one downloads (it waits its turn in pacedRead)
        if (readAhead != null && direct != null) scope.launch { runCatching { pacedRead(readAhead, cookieFile) } }
        // the streams yt-dlp would pick are plain downloads: fetch them ourselves, no Python to start
        val plan = if (direct != null && info != null) runCatching { planDirect(info.readText(), choice) }.getOrNull() else null
        if (plan != null) {
            ensureReady()
            try {
                return direct!!.run(plan, workDir, parts, onProgress, onMerging, target)
            } catch (e: IOException) {
                Log.i("Raftaaro", "direct download failed (${e.message}); handing it to yt-dlp")
            }
        }
        File(workDir, "direct").deleteRecursively()
        info?.let { info ->
            try {
                return once(info)
            } catch (e: YtDlpError) {
                // stale or unusable reading: forget it and read the page as usual
                Log.i("Raftaaro", "download from saved info failed (${e.message}); reading the link again")
                info.delete()
            }
        }
        return try {
            once(null)
        } catch (e: YtDlpError) {
            // an F-Droid build tells the user to update from Settings instead
            if (e.message != OUTDATED_MESSAGE || !BuildConfig.YTDLP_AUTO_UPDATE) throw e
            updateNow()
            once(null)
        }
    }

    private suspend fun downloadOnce(
        jobId: String, url: String, choice: VideoChoice?, workDir: File, cookieFile: File?, expected: Long?,
        onProgress: (VideoProgress) -> Unit, onMerging: () -> Unit, info: File?, paced: Boolean
    ): File {
        val tracker = ProgressTracker(expected)
        val args = downloadArgs(url, choice, workDir.absolutePath, cookieFile?.absolutePath, info?.absolutePath, paced)
        // with --load-info-json the URL must not be given too, or yt-dlp would read the page anyway
        val req = (if (info != null) YoutubeDLRequest(emptyList<String>()) else YoutubeDLRequest(url)).addCommands(args)
        val t0 = System.currentTimeMillis()
        var started = false
        exec(req, jobId) { line ->
            val wasMerging = tracker.merging
            tracker.onLine(line) { p -> File(p).takeIf { it.exists() }?.length() }?.let {
                if (!started) {
                    started = true
                    Log.i("Raftaaro", "download timing: first bytes after ${System.currentTimeMillis() - t0} ms (saved info: ${info != null})")
                }
                onProgress(it)
            }
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

    /**
     * Checks for a new yt-dlp at most once a day. Failures are ignored (next start tries again).
     * Never in an F-Droid build ([BuildConfig.YTDLP_AUTO_UPDATE] off): the user updates from Settings.
     */
    suspend fun updateIfDue(settings: SettingsStore) {
        if (!BuildConfig.YTDLP_AUTO_UPDATE) return
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
