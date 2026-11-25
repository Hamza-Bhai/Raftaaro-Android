package io.github.hamzabhai.raftaaro.http

import io.github.hamzabhai.raftaaro.core.ByteRange
import io.github.hamzabhai.raftaaro.core.sanitizeFileName
import io.github.hamzabhai.raftaaro.core.splitRanges
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.URLDecoder
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext

data class HttpProgress(val received: Long, val total: Long?, val speedBps: Long)

data class HttpResult(val file: File, val fileName: String)

@Serializable
private data class Meta(val url: String, val total: Long?, val ranges: List<ByteRange>?)

private data class Probe(val finalUrl: String, val total: Long?, val ranges: Boolean, val fileName: String)

private const val PROGRESS_MS = 250L
private const val SPEED_WINDOW_MS = 3000L

/** File name from Content-Disposition, else the last path segment of the (redirected) URL. */
internal fun fileNameFrom(res: Response): String {
    val cd = res.header("Content-Disposition").orEmpty()
    val star = Regex("""filename\*=(?:UTF-8'')?([^;]+)""", RegexOption.IGNORE_CASE).find(cd)?.groupValues?.get(1)
    val plain = Regex("""filename="?([^";]+)"?""", RegexOption.IGNORE_CASE).find(cd)?.groupValues?.get(1)
    val name = when {
        star != null -> runCatching { URLDecoder.decode(star.trim().trim('"'), "UTF-8") }.getOrDefault(star)
        plain != null -> plain.trim()
        else -> runCatching { URLDecoder.decode(res.request.url.pathSegments.lastOrNull().orEmpty(), "UTF-8") }.getOrDefault("")
    }
    return sanitizeFileName(name)
}

/** What a link points at. `isFile` is false for web pages (which may hold a video for yt-dlp). */
data class FileInfo(val name: String, val size: Long?, val isFile: Boolean)

/** Name, size and kind of a link, from its first byte, for the share sheet. */
suspend fun inspectFile(client: OkHttpClient, url: String): FileInfo = withContext(Dispatchers.IO) {
    val res = client.newCall(Request.Builder().url(url).header("Range", "bytes=0-0").build()).execute()
    res.use {
        if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
        val size = if (it.code == 206) Regex("""/(\d+)\s*$""").find(it.header("Content-Range").orEmpty())?.groupValues?.get(1)?.toLong()
        else it.header("Content-Length")?.toLongOrNull()?.takeIf { l -> l > 0 }
        val type = it.header("Content-Type").orEmpty().lowercase()
        FileInfo(fileNameFrom(it), size, !type.startsWith("text/html") && !type.startsWith("application/xhtml"))
    }
}

/**
 * Multi-part HTTP(S) download with resume. Parts live in `workDir/parts/` until joined into
 * `workDir/out/<name>`. Cancelling the coroutine pauses; running again with the same `workDir` resumes.
 */
class HttpDownload(
    private val client: OkHttpClient,
    val url: String,
    private val workDir: File,
    private val parts: Int
) {
    private val received = AtomicLong(0)
    private val calls: MutableSet<Call> = Collections.synchronizedSet(mutableSetOf())
    private val samples = ArrayDeque<Pair<Long, Long>>()
    private var lastEmit = 0L
    private var total: Long? = null

    suspend fun run(onProgress: (HttpProgress) -> Unit): HttpResult = withContext(Dispatchers.IO) {
        coroutineScope {
            // cancelling this coroutine aborts the blocking reads of every part
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    synchronized(calls) { calls.forEach { it.cancel() } }
                }
            }
            try {
                download(onProgress)
            } catch (e: IOException) {
                // aborted reads after a pause surface as "Socket closed": report the pause, not an error
                coroutineContext.ensureActive()
                throw e
            } finally {
                watcher.cancel()
            }
        }
    }

    private suspend fun download(onProgress: (HttpProgress) -> Unit): HttpResult {
        val probe = probe()
        total = probe.total
        val partsDir = File(workDir, "parts")
        val metaFile = File(partsDir, "meta.json")
        val old = readMeta(metaFile)
        if (old == null || old.url != url || old.total != probe.total || !probe.ranges) {
            partsDir.deleteRecursively()
            partsDir.mkdirs()
            val plan = if (probe.ranges && probe.total != null) splitRanges(probe.total, parts) else null
            metaFile.writeText(Json.encodeToString(Meta.serializer(), Meta(url, probe.total, plan)))
        }
        val plan = readMeta(metaFile)!!.ranges
        if (plan != null) {
            received.set(plan.indices.sumOf { File(partsDir, "part-$it").length() })
            coroutineScope {
                plan.forEachIndexed { i, r -> launch { fetchPart(probe.finalUrl, File(partsDir, "part-$i"), r, onProgress) } }
            }
        } else {
            received.set(0)
            fetchPart(probe.finalUrl, File(partsDir, "part-0"), null, onProgress)
        }
        val out = File(workDir, "out").apply { mkdirs() }
        val file = File(out, probe.fileName)
        FileOutputStream(file).use { dst ->
            for (i in 0 until (plan?.size ?: 1)) File(partsDir, "part-$i").inputStream().use { it.copyTo(dst) }
        }
        partsDir.deleteRecursively()
        emit(onProgress, force = true)
        return HttpResult(file, probe.fileName)
    }

    private fun readMeta(f: File): Meta? =
        runCatching { Json.decodeFromString(Meta.serializer(), f.readText()) }.getOrNull()

    private fun execute(request: Request): Response {
        val call = client.newCall(request)
        calls += call
        try {
            val res = call.execute()
            if (!res.isSuccessful) {
                res.close()
                throw IOException("HTTP ${res.code}")
            }
            return res
        } catch (e: Exception) {
            calls -= call
            throw e
        }
    }

    private fun probe(): Probe {
        val res = execute(Request.Builder().url(url).header("Range", "bytes=0-0").build())
        res.use {
            val name = fileNameFrom(it)
            if (it.code == 206) {
                Regex("""/(\d+)\s*$""").find(it.header("Content-Range").orEmpty())?.let { m ->
                    return Probe(it.request.url.toString(), m.groupValues[1].toLong(), true, name)
                }
            }
            val len = it.header("Content-Length")?.toLongOrNull()?.takeIf { l -> l > 0 }
            return Probe(it.request.url.toString(), len, false, name)
        }
    }

    private suspend fun fetchPart(finalUrl: String, file: File, range: ByteRange?, onProgress: (HttpProgress) -> Unit) {
        val req = Request.Builder().url(finalUrl)
        if (range != null) {
            val from = range.start + file.length()
            if (from > range.end) return
            req.header("Range", "bytes=$from-${range.end}")
        }
        try {
            readPart(req.build(), file, range, onProgress)
        } catch (e: IOException) {
            coroutineContext.ensureActive()
            throw e
        }
    }

    private suspend fun readPart(request: Request, file: File, range: ByteRange?, onProgress: (HttpProgress) -> Unit) {
        val res = execute(request)
        res.use {
            if (range != null && it.code != 206) throw IOException("Server stopped supporting resume")
            val source = it.body!!.source()
            FileOutputStream(file, range != null).use { dst ->
                val buf = ByteArray(64 * 1024)
                val stream = source.inputStream()
                while (true) {
                    coroutineContext.ensureActive()
                    val n = stream.read(buf)
                    if (n < 0) break
                    dst.write(buf, 0, n)
                    received.addAndGet(n.toLong())
                    emit(onProgress, force = false)
                }
            }
        }
        if (range != null && range.start + file.length() <= range.end) throw IOException("Connection closed early")
    }

    @Synchronized
    private fun emit(onProgress: (HttpProgress) -> Unit, force: Boolean) {
        val now = System.currentTimeMillis()
        val got = received.get()
        samples.addLast(now to got)
        while (samples.size > 1 && now - samples.first().first > SPEED_WINDOW_MS) samples.removeFirst()
        if (!force && now - lastEmit < PROGRESS_MS) return
        lastEmit = now
        val (t0, b0) = samples.first()
        val dt = (now - t0) / 1000.0
        onProgress(HttpProgress(got, total, if (dt > 0) ((got - b0) / dt).toLong() else 0))
    }
}
