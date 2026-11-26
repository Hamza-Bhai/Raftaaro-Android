package io.github.hamzabhai.raftaaro.engine

import android.util.Log
import io.github.hamzabhai.raftaaro.core.DirectOutput
import io.github.hamzabhai.raftaaro.core.DirectPlan
import io.github.hamzabhai.raftaaro.core.VideoProgress
import io.github.hamzabhai.raftaaro.http.HttpDownload
import io.github.hamzabhai.raftaaro.http.HttpProgress
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException

/** YouTube serves long single requests slowly; yt-dlp asks for 10 MB at a time too. */
private const val REQUEST_BYTES = 10L * 1024 * 1024

/** The bundled ffmpeg, run directly instead of through yt-dlp, so no Python has to start. */
class Ffmpeg(private val bundled: Bundled) {
    /** Runs ffmpeg with `args`; on cancel kills it. Throws [IOException] with its last lines on failure. */
    suspend fun run(args: List<String>) {
        bundled.run("ffmpeg", listOf("-y", "-nostdin", "-hide_banner", "-loglevel", "error") + args)
    }
}

/**
 * Fetches the streams of a [DirectPlan] with plain HTTP (several connections each) and joins or
 * converts them with ffmpeg: what yt-dlp would do, without starting Python. Pausing keeps the parts.
 */
class DirectDownload(private val http: OkHttpClient, private val ffmpeg: Ffmpeg) {
    suspend fun run(
        plan: DirectPlan, workDir: File, parts: Int,
        onProgress: (VideoProgress) -> Unit, onMerging: () -> Unit
    ): File {
        val dir = File(workDir, "direct")
        val t0 = System.currentTimeMillis()
        var started = false
        val latest = arrayOfNulls<HttpProgress>(plan.streams.size)
        val expected = plan.streams.map { it.size }
        fun emit() = synchronized(latest) {
            val received = latest.sumOf { it?.received ?: 0 }
            if (!started && received > 0) {
                started = true
                Log.i("Raftaaro", "download timing: first bytes after ${System.currentTimeMillis() - t0} ms (direct)")
            }
            val totals = latest.mapIndexed { i, p -> p?.total ?: expected[i] }
            onProgress(VideoProgress(received, totals.takeIf { t -> t.all { it != null } }?.sumOf { it!! }, latest.sumOf { it?.speedBps ?: 0 }))
        }
        val files = coroutineScope {
            plan.streams.mapIndexed { i, s ->
                async {
                    HttpDownload(http, s.url, File(dir, "s$i"), parts, s.headers, REQUEST_BYTES).run {
                        latest[i] = it
                        emit()
                    }.file
                }
            }.awaitAll()
        }
        onMerging()
        val out = File(workDir, plan.fileName)
        val inputs = files.flatMap { listOf("-i", it.absolutePath) }
        ffmpeg.run(
            inputs + when (plan.output) {
                DirectOutput.MERGE_MP4 -> listOf("-map", "0:v:0", "-map", "1:a:0", "-c", "copy", "-movflags", "+faststart")
                DirectOutput.REMUX_MP4 -> listOf("-c", "copy", "-movflags", "+faststart")
                DirectOutput.MERGE_MKV -> listOf("-map", "0:v:0", "-map", "1:a:0", "-c", "copy")
                DirectOutput.REMUX_MKV -> listOf("-c", "copy")
                DirectOutput.REMUX_M4A -> listOf("-vn", "-c", "copy", "-movflags", "+faststart")
                DirectOutput.MP3 -> listOf("-vn", "-c:a", "libmp3lame", "-q:a", "0")
            } + out.absolutePath
        )
        dir.deleteRecursively()
        onProgress(VideoProgress(out.length(), out.length(), 0))
        return out
    }
}
