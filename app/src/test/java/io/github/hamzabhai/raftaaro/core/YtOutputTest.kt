package io.github.hamzabhai.raftaaro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun fixture(name: String): String =
    YtOutputTest::class.java.getResource("/probe/$name")!!.readText()

class YtOutputTest {
    @Test fun `parses an RFTP line`() =
        assertEquals(YtProgress(1048576, 4194304, false, 524288, 6), parseProgress("RFTP 1048576 4194304 NA 524288 6"))

    @Test fun `uses the estimate when the total is NA`() =
        assertEquals(YtProgress(10, 201, true, null, null), parseProgress("RFTP 10 NA 200.5 NA NA"))

    @Test fun `ignores other lines`() = assertNull(parseProgress("[download] Destination: x.mp4"))

    @Test fun `asks for sign-in on private and login-required content`() {
        assertEquals(SIGN_IN_MESSAGE, classifyYtDlpError("ERROR: [youtube] a: Private video. Sign in if you have been granted access"))
        assertEquals(
            SIGN_IN_MESSAGE,
            classifyYtDlpError("ERROR: [Instagram] a: Requested content is not available, rate-limit reached or login required")
        )
    }

    @Test fun `suggests updating on extraction and 403 errors`() {
        assertEquals(OUTDATED_MESSAGE, classifyYtDlpError("ERROR: unable to download video data: HTTP Error 403: Forbidden"))
        assertEquals(OUTDATED_MESSAGE, classifyYtDlpError("ERROR: [youtube] a: Unable to extract initial player response"))
    }

    @Test fun `recognises YouTube rate limiting`() {
        assertEquals(RATE_LIMIT_MESSAGE, classifyYtDlpError("ERROR: [youtube] a: Sign in to confirm you're not a bot. Use --cookies-from-browser"))
        assertEquals(RATE_LIMIT_MESSAGE, classifyYtDlpError("ERROR: unable to download webpage: HTTP Error 429: Too Many Requests"))
    }

    @Test fun `reports unavailable videos`() =
        assertEquals("Video unavailable", classifyYtDlpError("ERROR: [youtube] a: Video unavailable"))

    @Test fun `falls back to the last ERROR line`() {
        assertEquals("something odd", classifyYtDlpError("WARNING: x\nERROR: something odd\n"))
        assertEquals("Download failed", classifyYtDlpError(""))
    }

    @Test fun `returns VideoInfo for a single video`() {
        val r = parseProbe("https://www.youtube.com/watch?v=abc", fixture("raw-video.json")) as ProbeResult.Video
        assertEquals("Test video — ✓ unicode", r.info.title)
        assertEquals(624, r.info.durationSec)
        assertEquals("CatChannel", r.info.uploader)
        assertEquals("https://i.ytimg.com/vi/abc/hq.jpg", r.info.thumbnail)
        assertEquals(3, r.info.formats.size)
        val f = r.info.formats[2]
        assertEquals("137", f.formatId)
        assertEquals(1080, f.height)
        assertNull(f.filesize)
        assertEquals(100_000_000L, f.filesizeApprox)
    }

    @Test fun `returns PlaylistInfo with entry urls`() = assertEquals(
        ProbeResult.Playlist(
            PlaylistInfo(
                "https://www.youtube.com/playlist?list=PL1", "My playlist",
                listOf(
                    PlaylistEntry("https://www.youtube.com/watch?v=v1", "First", 61),
                    PlaylistEntry("https://www.youtube.com/watch?v=v2", "Second", null)
                )
            )
        ),
        parseProbe("https://www.youtube.com/playlist?list=PL1", fixture("raw-playlist.json"))
    )

    @Test fun `opens the Videos tab for a channel`() = assertEquals(
        ProbeResult.OpenTab("https://www.youtube.com/@chan/videos"),
        parseProbe("https://www.youtube.com/@chan", fixture("raw-channel.json"))
    )

    @Test fun `a stream that is live now is refused with a clear message`() {
        for (status in listOf("\"is_live\": true", "\"live_status\": \"is_live\"", "\"live_status\": \"is_upcoming\"")) {
            val e = runCatching { parseProbe("https://www.youtube.com/@chan/live", "{\"title\": \"radio\", $status, \"formats\": []}") }.exceptionOrNull()
            assertEquals(status, LIVE_MESSAGE, e?.message)
        }
    }

    @Test fun `a stream that has ended is an ordinary video`() {
        val r = parseProbe("https://www.youtube.com/watch?v=a", "{\"title\": \"was\", \"is_live\": false, \"live_status\": \"was_live\", \"formats\": []}")
        assertEquals("was", (r as ProbeResult.Video).info.title)
    }

    @Test fun `a site yt-dlp does not know gets a short message`() =
        assertEquals(UNSUPPORTED_MESSAGE, classifyYtDlpError("ERROR: Unsupported URL: https://www.threads.com/@a/post/B?xmt=long"))

    @Test fun `unreadable output is a friendly error`() {
        val e = runCatching { parseProbe("https://x.com/a", "not json") }.exceptionOrNull()
        assertEquals("Could not read the answer from yt-dlp", e?.message)
    }
}

/** Port of the desktop's YtDlpDownload progress tests; the lines are what tests/fixtures/fake-ytdlp.mjs prints. */
class ProgressTrackerTest {
    private val video = listOf("[download] Destination: v.f1.mp4", "RFTP 500 1000 NA 100 5", "RFTP 1000 1000 NA 100 0")
    private val rest = listOf(
        "[download] Sleeping 3.00 seconds ...",
        "[download] Destination: v.f2.m4a",
        "RFTP 200 400 NA 100 2",
        "[Merger] Merging formats into \"v.mp4\"",
        "RFTFILE:/w/v.mp4"
    )

    private fun run(lines: List<String>, expected: Long?, sizes: Map<String, Long> = emptyMap()): Pair<ProgressTracker, List<VideoProgress>> {
        val t = ProgressTracker(expected)
        val out = lines.mapNotNull { t.onLine(it) { p -> sizes[p] } }
        return t to out
    }

    @Test fun `reports combined progress, merging, parts and the final path`() {
        val (t, p) = run(video + rest, null)
        assertTrue(p.contains(VideoProgress(1200, 1400, 100)))
        assertTrue(t.merging)
        assertEquals(listOf("v.f1.mp4", "v.f2.m4a", "v.mp4"), t.parts)
        assertEquals("/w/v.mp4", t.finalPath)
    }

    @Test fun `counts the audio in the total while the video is still downloading`() {
        val (_, p) = run(listOf("RFTSIZE 1400") + video + rest, null)
        assertEquals(VideoProgress(500, 1400, 100), p[0])
        assertTrue(p.contains(VideoProgress(1200, 1400, 100)))
    }

    @Test fun `prefers yt-dlp's size of the chosen formats over the dialog estimate`() =
        assertEquals(VideoProgress(500, 1400, 100), run(listOf("RFTSIZE 1400") + video + rest, 99999).second[0])

    @Test fun `keeps the dialog estimate when yt-dlp does not know the size`() =
        assertEquals(VideoProgress(500, 1400, 100), run(listOf("RFTSIZE NA") + video + rest, 1400).second[0])

    @Test fun `works the size out from bitrate and duration when yt-dlp has no size`() =
        // 0.008 kbit/s for 1400 s = 1400 bytes
        assertEquals(VideoProgress(500, 1400, 100), run(listOf("RFTSIZE NA 0.008 1400") + video + rest, null).second[0])

    @Test fun `ignores yt-dlp's jumpy guesses for streamed (HLS) formats when the size is known`() {
        val hls = listOf("[download] Destination: v.f1.mp4", "RFTP 500 NA 5000 100 5", "RFTP 1000 NA 900 100 0")
        val (_, p) = run(listOf("RFTSIZE 1400") + hls + rest, null)
        assertEquals(p.map { 1400L }, p.map { it.total })
    }

    @Test fun `never shows a total below what was already received`() {
        val (_, p) = run(listOf("RFTSIZE 600") + video + rest, null)
        p.forEach { assertTrue(it.total!! >= it.received) }
    }

    @Test fun `shows no speed while yt-dlp waits between streams`() =
        assertTrue(run(video + rest, null).second.contains(VideoProgress(1000, 1000, 0)))

    @Test fun `counts a stream finished in an earlier run after resuming`() {
        val resumed = listOf("[download] /w/v.f1.mp4 has already been downloaded")
        val (t, p) = run(listOf("RFTSIZE 1400") + resumed + rest, null, mapOf("/w/v.f1.mp4" to 1000L))
        assertEquals(VideoProgress(1200, 1400, 100), p[0])
        assertTrue(t.parts.contains("/w/v.f1.mp4"))
    }

    @Test fun `remembers an already downloaded file as the result`() {
        val (t, _) = run(listOf("[download] /w/v.mp4 has already been downloaded"), null)
        assertEquals("/w/v.mp4", t.seenPath)
    }
}
