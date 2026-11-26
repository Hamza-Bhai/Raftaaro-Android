package io.github.hamzabhai.raftaaro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstLookTest {
    private val url = "https://www.youtube.com/watch?v=abc"

    @Test fun `reads title, channel and thumbnail from oEmbed`() {
        val info = parseOembed(url, """{"title":"My video","author_name":"Chan","thumbnail_url":"https://i.ytimg.com/vi/abc/hqdefault.jpg","type":"video"}""")!!
        assertEquals(url, info.url)
        assertEquals("My video", info.title)
        assertEquals("Chan", info.uploader)
        assertEquals("https://i.ytimg.com/vi/abc/hqdefault.jpg", info.thumbnail)
        assertNull(info.durationSec)
        assertTrue(info.formats.isEmpty())
    }

    @Test fun `no title or odd json gives nothing`() {
        assertNull(parseOembed(url, """{"author_name":"Chan"}"""))
        assertNull(parseOembed(url, "Not Found"))
    }

    @Test fun `asks oEmbed for the link`() =
        assertEquals(
            "https://www.youtube.com/oembed?format=json&url=https%3A%2F%2Fyoutu.be%2Fabc%3Fsi%3Dx",
            oembedUrl("https://youtu.be/abc?si=x")
        )
}
