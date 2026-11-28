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
 * Where a finished file can be written in its final place (shared storage), so it is not
 * written once in the app's folder and then copied. [open] returns null where that is not possible.
 */
interface SaveTarget {
    /** A file to write `name` into, already in its final place; null to write to the work folder. */
    suspend fun open(name: String): File?
    /** Gives up the file [open] made (it is deleted). */
    suspend fun abort()
}

/**
 * Fetches the streams of a [DirectPlan] with plain HTTP (several connections each) and joins or
 * converts them with ffmpeg: what yt-dlp would do, without starting Python. Pausing keeps the parts.
 */
class DirectDownload(private val http: OkHttpClient, private val ffmpeg: Ffmpeg) {
    suspend fun run(
        plan: DirectPlan, workDir: File, parts: Int,
        onProgress: (VideoProgress) -> Unit, onMerging: () -> Unit, target: SaveTarget? = null
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
                    HttpDownload(http, s.url, File(dir, "s$i"), parts, s.headers, REQUEST_BYTES).fetchParts {
                        latest[i] = it
                        emit()
                    }
                }
            }.awaitAll()
        }
        onMerging()
        val tMerge = System.currentTimeMillis()
        Log.i("Raftaaro", "finish timing: streams fetched after ${tMerge - t0} ms (${files.sumOf { p -> p.sumOf { it.length() } } shr 20} MB, ${plan.output})")
        // ffmpeg reads each stream's parts as one file (concatf: a list of them), so they are not joined first
        val inputs = files.flatMapIndexed { i, p ->
            val list = File(dir, "s$i.parts").apply { writeText(p.joinToString("\n") { it.absolutePath }) }
            listOf("-i", if (p.size == 1) p[0].absolutePath else "concatf:${list.absolutePath}")
        }
        // the format is named: a shared-storage target's path has no extension to guess it from
        val args = when (plan.output) {
            DirectOutput.MERGE_MP4 -> listOf("-map", "0:v:0", "-map", "1:a:0", "-c", "copy", "-f", "mp4")
            DirectOutput.MERGE_MKV -> listOf("-map", "0:v:0", "-map", "1:a:0", "-c", "copy", "-f", "matroska")
            DirectOutput.REMUX_MP4 -> listOf("-c", "copy", "-f", "mp4")
            DirectOutput.REMUX_MKV -> listOf("-c", "copy", "-f", "matroska")
            DirectOutput.REMUX_M4A -> listOf("-vn", "-c", "copy", "-f", "ipod")
            DirectOutput.MP3 -> listOf("-vn", "-c:a", "libmp3lame", "-q:a", "0", "-f", "mp3")
        }
        val local = File(workDir, plan.fileName)
        // no "-movflags +faststart": it writes the whole file a second time, and players on the phone do not need it
        val shared = target?.let { runCatching { it.open(plan.fileName) }.getOrNull() }
        val out = if (shared != null) {
            try {
                ffmpeg.run(inputs + args + shared.absolutePath)
                shared
            } catch (e: IOException) {
                Log.i("Raftaaro", "ffmpeg could not write to shared storage (${e.message}); writing to the app folder")
                target.abort()
                ffmpeg.run(inputs + args + local.absolutePath)
                local
            }
        } else {
            ffmpeg.run(inputs + args + local.absolutePath)
            local
        }
        Log.i("Raftaaro", "finish timing: ffmpeg took ${System.currentTimeMillis() - tMerge} ms (${if (out == local) "app folder" else "straight into Downloads"})")
        dir.deleteRecursively()
        onProgress(VideoProgress(out.length(), out.length(), 0))
        return out
    }
}
