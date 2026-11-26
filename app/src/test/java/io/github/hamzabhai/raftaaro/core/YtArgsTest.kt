package io.github.hamzabhai.raftaaro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YtArgsTest {
    private val pacing = listOf("--sleep-requests", "1", "--sleep-interval", "3", "--max-sleep-interval", "8")

    private fun List<String>.containsRun(run: List<String>) = windowed(run.size).any { it == run }

    @Test fun `probes a watch link as one video`() {
        val args = probeArgs("https://www.youtube.com/watch?v=abc&list=PL1", null)
        assertTrue(args.containsRun(listOf("-J", "--flat-playlist")))
        assertTrue(args.contains("--no-playlist"))
    }

    @Test fun `probes a playlist link as a playlist`() =
        assertFalse(probeArgs("https://www.youtube.com/playlist?list=PL1", null).contains("--no-playlist"))

    @Test fun `paces YouTube playlist downloads only`() {
        assertTrue(downloadArgs("https://youtu.be/x", null, "/w", null, paced = true).containsRun(pacing))
        assertFalse(downloadArgs("https://www.instagram.com/reel/x", null, "/w", null, paced = true).containsRun(pacing))
    }

    @Test fun `a single video starts at once, without pauses`() {
        val args = downloadArgs("https://youtu.be/x", null, "/w", null, paced = false)
        assertFalse(args.contains("--sleep-interval"))
        assertFalse(args.contains("--sleep-requests"))
    }

    @Test fun `reading a link is not slowed down by pacing`() =
        assertFalse(probeArgs("https://youtu.be/x", null).contains("--sleep-requests"))

    @Test fun `a download can reuse the info read for the share sheet`() {
        val args = downloadArgs("https://youtu.be/x", null, "/w", null, infoJson = "/c/info.json")
        assertTrue(args.containsRun(listOf("--load-info-json", "/c/info.json")))
        assertFalse(downloadArgs("https://youtu.be/x", null, "/w", null).contains("--load-info-json"))
    }

    @Test fun `passes cookies only when a file is given`() {
        assertFalse(probeArgs("https://youtu.be/x", null).contains("--cookies"))
        assertTrue(probeArgs("https://youtu.be/x", "/c/yt.txt").containsRun(listOf("--cookies", "/c/yt.txt")))
    }

    @Test fun `downloadArgs contains --continue and the work dir template`() {
        val args = downloadArgs("https://youtu.be/x", VideoChoice(MediaType.VIDEO, 720), "/w", null)
        assertTrue(args.contains("--continue"))
        assertEquals("/w/%(title).150B [%(id)s] 720p.%(ext)s", args[args.indexOf("-o") + 1])
        assertTrue(args.containsRun(listOf("-f", "bv*[height<=720]+ba/b[height<=720]/bv*+ba/b")))
        assertTrue(args.containsRun(listOf("--progress-template", "download:$PROGRESS_TEMPLATE")))
    }

    @Test fun `keeps best-quality and audio names plain`() {
        val best = downloadArgs("https://youtu.be/x", VideoChoice(MediaType.VIDEO, null), "/w", null)
        assertEquals("/w/%(title).150B [%(id)s].%(ext)s", best[best.indexOf("-o") + 1])
        val mp3 = downloadArgs("https://youtu.be/x", VideoChoice(MediaType.AUDIO, null, AudioFormat.MP3), "/w", null)
        assertEquals("/w/%(title).150B [%(id)s].%(ext)s", mp3[mp3.indexOf("-o") + 1])
    }

    @Test fun `a file job without a choice takes the best single file`() =
        assertTrue(downloadArgs("https://example.com/v", null, "/w", null).containsRun(listOf("-f", "b")))
}
