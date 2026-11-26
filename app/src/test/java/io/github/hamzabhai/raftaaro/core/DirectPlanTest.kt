package io.github.hamzabhai.raftaaro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectPlanTest {
    private fun fmt(
        id: String, height: Int?, vcodec: String, acodec: String, ext: String,
        protocol: String = "https", size: Long? = 1000, tbr: Double = 100.0, abr: Double? = null
    ) = """{"format_id":"$id","url":"https://g.example/$id","protocol":"$protocol","ext":"$ext",""" +
        (if (height != null) """"height":$height,""" else "") +
        """"vcodec":"$vcodec","acodec":"$acodec",""" + (if (size != null) """"filesize":$size,""" else "") +
        (if (abr != null) """"abr":$abr,""" else "") +
        """"tbr":$tbr,"http_headers":{"User-Agent":"UA-$id"}}"""

    private fun info(vararg formats: String, title: String = "My: video") =
        """{"id":"abc123","title":"$title","formats":[${formats.joinToString(",")}]}"""

    private val typical = info(
        fmt("140", null, "none", "mp4a.40.2", "m4a", abr = 129.0),
        fmt("251", null, "none", "opus", "webm", abr = 135.0),
        fmt("18", 360, "avc1.42001E", "mp4a.40.2", "mp4", tbr = 500.0),
        fmt("134", 360, "avc1.4D401E", "none", "mp4", tbr = 300.0),
        fmt("243", 360, "vp9", "none", "webm", tbr = 250.0),
        fmt("136", 720, "avc1.4D401F", "none", "mp4", size = 26_000, tbr = 900.0),
        fmt("247", 720, "vp9", "none", "webm", tbr = 800.0),
        fmt("232", 720, "avc1.4D401F", "none", "mp4", protocol = "m3u8_native", size = null, tbr = 1000.0),
        fmt("137", 1080, "avc1.640028", "none", "mp4", tbr = 2000.0),
        fmt("271", 1440, "vp9", "none", "webm", tbr = 5000.0)
    )

    private val v720 = VideoChoice(MediaType.VIDEO, 720)

    @Test fun `a height picks the H264 stream there plus m4a audio`() {
        val p = planDirect(typical, v720)!!
        assertEquals("https://g.example/136", p.video!!.url)
        assertEquals(26_000L, p.video!!.size)
        assertEquals(mapOf("User-Agent" to "UA-136"), p.video!!.headers)
        assertEquals("https://g.example/140", p.audio!!.url)
        assertEquals(DirectOutput.MERGE_MP4, p.output)
        assertEquals("My_ video [abc123] 720p.mp4", p.fileName)
    }

    @Test fun `best takes the highest resolution, whatever its codec`() {
        val p = planDirect(typical, VideoChoice(MediaType.VIDEO, null))!!
        assertEquals("https://g.example/271", p.video!!.url)
        // VP9 in an MP4 plays as sound only in many phones' own players (Transsion/MediaTek); MKV plays
        assertEquals(DirectOutput.MERGE_MKV, p.output)
        assertEquals("My_ video [abc123].mkv", p.fileName)
    }

    @Test fun `an Instagram reel's VP9 streams with m4a are joined into an mkv`() {
        val reel = info(
            fmt("dash-a", null, "none", "mp4a.40.5", "m4a"), fmt("dash-720v", 1280, "vp09.00.31.08", "none", "mp4"),
            fmt("dash-1080v", 1920, "vp09.00.40.08", "none", "mp4")
        )
        val p = planDirect(reel, VideoChoice(MediaType.VIDEO, null))!!
        assertEquals("https://g.example/dash-1080v", p.video!!.url)
        assertEquals(DirectOutput.MERGE_MKV, p.output)
        assertTrue(p.fileName.endsWith("].mkv"))
    }

    @Test fun `a VP9 stream with its own audio is remuxed into an mkv`() {
        val p = planDirect(info(fmt("v", 720, "vp9", "opus", "webm")), v720)!!
        assertEquals(DirectOutput.REMUX_MKV, p.output)
        assertEquals("My_ video [abc123] 720p.mkv", p.fileName)
    }

    @Test fun `a height above every stream falls back to the best there is`() =
        assertEquals("https://g.example/136", planDirect(info(
            fmt("140", null, "none", "mp4a.40.2", "m4a"), fmt("136", 720, "avc1", "none", "mp4")
        ), VideoChoice(MediaType.VIDEO, 1080))!!.video!!.url)

    @Test fun `a height below every stream takes the best, as yt-dlp's selector does`() =
        assertEquals("https://g.example/137", planDirect(info(
            fmt("140", null, "none", "mp4a.40.2", "m4a"), fmt("136", 720, "avc1", "none", "mp4"), fmt("137", 1080, "avc1", "none", "mp4")
        ), VideoChoice(MediaType.VIDEO, 360))!!.video!!.url)

    @Test fun `leaves it to yt-dlp when the chosen stream is not a plain download`() =
        assertNull(planDirect(info(
            fmt("140", null, "none", "mp4a.40.2", "m4a"),
            fmt("232", 720, "avc1", "none", "mp4", protocol = "m3u8_native", tbr = 1000.0),
            fmt("136", 480, "avc1", "none", "mp4")
        ), v720))

    @Test fun `a stream with audio already needs no second download`() {
        val p = planDirect(info(fmt("18", 360, "avc1", "mp4a.40.2", "mp4")), VideoChoice(MediaType.VIDEO, 360))!!
        assertEquals("https://g.example/18", p.video!!.url)
        assertNull(p.audio)
        assertEquals(DirectOutput.REMUX_MP4, p.output)
    }

    @Test fun `leaves it to yt-dlp when separate video has no audio to join`() =
        assertNull(planDirect(info(fmt("136", 720, "avc1", "none", "mp4")), v720))

    @Test fun `mp3 takes the best audio and converts it`() {
        val p = planDirect(typical, VideoChoice(MediaType.AUDIO, null, AudioFormat.MP3))!!
        assertNull(p.video)
        assertEquals("https://g.example/251", p.audio!!.url)
        assertEquals(DirectOutput.MP3, p.output)
        assertEquals("My_ video [abc123].mp3", p.fileName)
    }

    @Test fun `m4a takes m4a audio as it is`() {
        val p = planDirect(typical, VideoChoice(MediaType.AUDIO, null, AudioFormat.M4A))!!
        assertEquals("https://g.example/140", p.audio!!.url)
        assertEquals(DirectOutput.REMUX_M4A, p.output)
        assertTrue(p.fileName.endsWith(".m4a"))
    }

    @Test fun `no choice, odd json or no formats leave it to yt-dlp`() {
        assertNull(planDirect(typical, null))
        assertNull(planDirect("not json", v720))
        assertNull(planDirect(info(), v720))
    }

    @Test fun `a long title is cut to fit, keeping the id and extension`() {
        val name = planDirect(info(fmt("18", 360, "avc1", "mp4a", "mp4"), title = "x".repeat(400)), VideoChoice(MediaType.VIDEO, 360))!!.fileName
        assertTrue(name.endsWith(" [abc123] 360p.mp4"))
        assertFalse(name.length > 200)
    }
}
