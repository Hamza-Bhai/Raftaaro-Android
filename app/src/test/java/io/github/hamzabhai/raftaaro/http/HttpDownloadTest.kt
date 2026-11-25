package io.github.hamzabhai.raftaaro.http

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.random.Random

class HttpDownloadTest {
    private val size = 2 * 1024 * 1024
    private var data = Random.nextBytes(size)
    private var ranges = true
    private var slow = false
    private var cutBody = false
    private val rangeRequests: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path ?: ""
                if (path == "/missing") return MockResponse().setResponseCode(404)
                if (path == "/redirect") return MockResponse().setResponseCode(302).setHeader("Location", "/file.bin")
                val range = request.getHeader("Range")
                if (range != null) rangeRequests += range
                var r = MockResponse().setHeader("Content-Type", "application/octet-stream")
                if (path == "/named") r = r.setHeader("Content-Disposition", "attachment; filename=\"My:File?.bin\"")
                if (ranges && range != null) {
                    val m = Regex("""bytes=(\d+)-(\d*)""").find(range)!!
                    val start = m.groupValues[1].toInt()
                    val end = m.groupValues[2].toIntOrNull()?.coerceAtMost(data.size - 1) ?: (data.size - 1)
                    r = r.setResponseCode(206).setHeader("Content-Range", "bytes $start-$end/${data.size}")
                        .setBody(Buffer().write(data, start, end - start + 1))
                } else if (ranges) {
                    r = r.setBody(Buffer().write(data))
                } else {
                    r = r.setChunkedBody(Buffer().write(data), 64 * 1024)
                }
                if (slow) r = r.throttleBody(8 * 1024, 20, TimeUnit.MILLISECONDS)
                if (cutBody && range != "bytes=0-0") r = r.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
                return r
            }
        }
        server.start()
    }

    @After fun stop() = server.shutdown()

    private fun dir(): File = Files.createTempDirectory("raftaaro-http-").toFile()
    private fun url(p: String) = server.url(p).toString()

    @Test fun `downloads with 8 parts and output equals source bytes`() = runBlocking {
        val result = HttpDownload(client, url("/file.bin"), dir(), 8).run {}
        assertEquals("file.bin", result.fileName)
        assertArrayEquals(data, result.file.readBytes())
        assertEquals(8, rangeRequests.count { it != "bytes=0-0" })
    }

    @Test fun `uses a sanitized name from Content-Disposition`() = runBlocking {
        assertEquals("My_File_.bin", HttpDownload(client, url("/named"), dir(), 2).run {}.fileName)
    }

    @Test fun `follows redirects`() = runBlocking {
        assertArrayEquals(data, HttpDownload(client, url("/redirect"), dir(), 4).run {}.file.readBytes())
    }

    @Test fun `falls back to single connection when ranges unsupported`() = runBlocking {
        ranges = false
        var lastTotal: Long? = -1
        val result = HttpDownload(client, url("/file.bin"), dir(), 8).run { lastTotal = it.total }
        assertArrayEquals(data, result.file.readBytes())
        assertNull(lastTotal)
    }

    /** Run until more than `fraction` of the bytes arrived, then cancel (= pause). */
    private fun pauseAfter(d: HttpDownload, fraction: Double) = runBlocking {
        val reached = CompletableDeferred<Unit>()
        val job = launch { d.run { if (it.received > size * fraction) reached.complete(Unit) } }
        reached.await()
        job.cancelAndJoin()
    }

    @Test fun `resumes from existing part files`() = runBlocking {
        slow = true
        val work = dir()
        pauseAfter(HttpDownload(client, url("/file.bin"), work, 4), 0.5)
        rangeRequests.clear()
        slow = false
        val result = HttpDownload(client, url("/file.bin"), work, 4).run {}
        assertArrayEquals(data, result.file.readBytes())
        val starts = rangeRequests.filter { it != "bytes=0-0" }.map { Regex("""bytes=(\d+)""").find(it)!!.groupValues[1].toInt() }
        val partStarts = (0..3).map { it * (size / 4) }
        assertTrue(starts.any { it !in partStarts })
    }

    @Test fun `restarts when remote size changed`() = runBlocking {
        slow = true
        val work = dir()
        pauseAfter(HttpDownload(client, url("/file.bin"), work, 4), 0.25)
        data = Random.nextBytes(size - 1000)
        slow = false
        assertArrayEquals(data, HttpDownload(client, url("/file.bin"), work, 4).run {}.file.readBytes())
    }

    @Test fun `keeps two simultaneous downloads of the same url apart`() = runBlocking {
        val a = async { HttpDownload(client, url("/file.bin"), dir(), 4).run {} }
        val b = async { HttpDownload(client, url("/file.bin"), dir(), 4).run {} }
        val (ra, rb) = a.await() to b.await()
        assertFalse(ra.file == rb.file)
        assertArrayEquals(data, ra.file.readBytes())
        assertArrayEquals(data, rb.file.readBytes())
    }

    @Test fun `404 throws without creating an output file`() = runBlocking {
        val work = dir()
        val e = runCatching { HttpDownload(client, url("/missing"), work, 4).run {} }.exceptionOrNull()
        assertEquals("HTTP 404", e?.message)
        assertFalse(File(work, "out").exists())
    }

    @Test fun `connection reset mid-body throws IOException`() = runBlocking {
        cutBody = true
        val e = runCatching { HttpDownload(client, url("/file.bin"), dir(), 4).run {} }.exceptionOrNull()
        assertTrue("got $e", e is IOException)
    }
}
