package io.github.hamzabhai.raftaaro.download

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hamzabhai.raftaaro.App
import io.github.hamzabhai.raftaaro.core.Job
import io.github.hamzabhai.raftaaro.core.JobKind
import io.github.hamzabhai.raftaaro.core.JobStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class DownloadQueueTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val graph = (ctx.applicationContext as App).graph
    private val data = Random.nextBytes(2 * 1024 * 1024)
    private var slow = false
    private val server = MockWebServer()
    private val created = mutableListOf<String>()

    @Before fun start() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                var r = MockResponse()
                if (range != null) {
                    val m = Regex("""bytes=(\d+)-(\d*)""").find(range)!!
                    val s = m.groupValues[1].toInt()
                    val e = m.groupValues[2].toIntOrNull()?.coerceAtMost(data.size - 1) ?: (data.size - 1)
                    r = r.setResponseCode(206).setHeader("Content-Range", "bytes $s-$e/${data.size}").setBody(Buffer().write(data, s, e - s + 1))
                } else {
                    r = r.setBody(Buffer().write(data))
                }
                return if (slow) r.throttleBody(16 * 1024, 20, TimeUnit.MILLISECONDS) else r
            }
        }
        server.start()
    }

    @After fun stop(): Unit = runBlocking {
        created.forEach { graph.queue.remove(it, deleteFile = true) }
        server.shutdown()
    }

    private suspend fun waitFor(id: String, ms: Long = 60_000, test: (Job) -> Boolean): Job =
        withTimeout(ms) { graph.jobs.jobs.first { list -> list.any { it.id == id && test(it) } }.first { it.id == id } }

    private fun read(uri: String) = ctx.contentResolver.openInputStream(Uri.parse(uri))!!.use { it.readBytes() }

    @Test fun aFileJobRunsToDoneAndItsFileIsReadable(): Unit = runBlocking {
        val name = "q-${UUID.randomUUID().toString().take(8)}.bin"
        val id = graph.queue.enqueue(server.url("/$name").toString(), name, JobKind.FILE, null, null, null)
        created += id
        val done = waitFor(id) { it.status == JobStatus.DONE || it.status == JobStatus.ERROR }
        assertEquals(null, done.error)
        assertEquals(JobStatus.DONE, done.status)
        assertEquals(name, done.fileName)
        assertArrayEquals(data, read(done.savedUri!!))
    }

    @Test fun pauseThenResumeFinishesWithTheRightBytes(): Unit = runBlocking {
        slow = true
        val name = "p-${UUID.randomUUID().toString().take(8)}.bin"
        val id = graph.queue.enqueue(server.url("/$name").toString(), name, JobKind.FILE, null, null, null)
        created += id
        waitFor(id) { it.receivedBytes > 0 }
        graph.queue.pause(id)
        assertEquals(JobStatus.PAUSED, graph.jobs.get(id)!!.status)
        slow = false
        graph.queue.resume(id)
        val done = waitFor(id) { it.status == JobStatus.DONE || it.status == JobStatus.ERROR }
        assertEquals(JobStatus.DONE, done.status)
        assertArrayEquals(data, read(done.savedUri!!))
    }
}
