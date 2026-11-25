package io.github.hamzabhai.raftaaro.http

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InspectFileTest {
    private val server = MockWebServer().apply { start() }
    private val client = OkHttpClient()

    @After fun stop() = server.shutdown()

    @Test fun `a binary file with an unknown extension is a file`() = runBlocking {
        server.enqueue(
            MockResponse().setResponseCode(206).setHeader("Content-Type", "application/octet-stream")
                .setHeader("Content-Range", "bytes 0-0/1073741824").setBody(Buffer().writeByte(0))
        )
        val info = inspectFile(client, server.url("/files/1Gb.dat").toString())
        assertEquals("1Gb.dat", info.name)
        assertEquals(1073741824L, info.size)
        assertTrue(info.isFile)
    }

    @Test fun `a web page is not a file`() = runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody("<html></html>"))
        assertFalse(inspectFile(client, server.url("/watch").toString()).isFile)
    }
}
