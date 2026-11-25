package io.github.hamzabhai.raftaaro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Same data as the desktop's tests/fixtures/yt-4k.json and muxed-only.json. */
private fun f(id: String, ext: String, h: Int?, v: String, a: String, size: Long?, approx: Long?, tbr: Double?) =
    YtFormat(id, ext, h, v, a, size, approx, tbr)

private val YT_4K = VideoInfo(
    "https://www.youtube.com/watch?v=abc", "Test video", 624, "CatChannel", "https://i.ytimg.com/vi/abc/hq.jpg",
    listOf(
        f("18", "mp4", 360, "avc1.42001E", "mp4a.40.2", 25_000_000, null, 500.0),
        f("137", "mp4", 1080, "avc1.640028", "none", 100_000_000, null, 4000.0),
        f("248", "webm", 1080, "vp9", "none", null, 90_000_000, 3500.0),
        f("313", "webm", 2160, "vp9", "none", 580_000_000, null, 18000.0),
        f("140", "m4a", null, "none", "mp4a.40.2", 10_000_000, null, 129.0),
        f("251", "webm", null, "none", "opus", 9_000_000, null, 140.0),
        f("sb0", "mhtml", 90, "none", "none", null, null, null)
    )
)

private val MUXED_ONLY = VideoInfo(
    "https://www.facebook.com/watch?v=1", "FB video", 30, null, null,
    listOf(
        f("sd", "mp4", 360, "h264", "aac", null, null, null),
        f("hd", "mp4", 720, "h264", "aac", 12_000_000, null, null)
    )
)

class FormatsTest {
    private fun List<QualityOption>.at(h: Int) = first { it.height == h }

    @Test fun `lists Best first then every real height, highest first`() {
        val opts = buildQualityOptions(YT_4K, MediaType.VIDEO)
        assertEquals(listOf("⭐ Best (2160p (4K))", "2160p (4K)", "1080p", "360p"), opts.map { it.label })
        assertEquals(listOf(null, 2160, 1080, 360), opts.map { it.height })
    }

    @Test fun `estimates size as video + best audio`() {
        val opts = buildQualityOptions(YT_4K, MediaType.VIDEO)
        assertEquals(110_000_000L, opts.at(1080).estBytes)
        assertEquals(590_000_000L, opts.at(2160).estBytes)
    }

    @Test fun `uses the muxed size alone when the format already has audio`() {
        val opts = buildQualityOptions(MUXED_ONLY, MediaType.VIDEO)
        assertEquals(listOf(null, 720, 360), opts.map { it.height })
        assertEquals(12_000_000L, opts.at(720).estBytes)
        assertNull(opts.at(360).estBytes)
    }

    @Test fun `offers MP3 and M4A for audio`() = assertEquals(
        listOf(
            QualityOption(null, "MP3", 10_000_000, AudioFormat.MP3),
            QualityOption(null, "M4A", 10_000_000, AudioFormat.M4A)
        ),
        buildQualityOptions(YT_4K, MediaType.AUDIO)
    )

    @Test fun `estimates from bitrate and duration when sizes are missing`() {
        val info = YT_4K.copy(
            formats = listOf(
                f("v", "mp4", 720, "avc1", "none", null, null, 1000.0),
                f("a", "m4a", null, "none", "mp4a", null, null, 128.0)
            )
        )
        // (1000 + 128) kbit/s * 624 s / 8 = 87,984,000 bytes
        assertEquals(87_984_000L, buildQualityOptions(info, MediaType.VIDEO).at(720).estBytes)
    }

    @Test fun `estimates with the H264 stream when one exists at that height`() {
        val info = YT_4K.copy(formats = YT_4K.formats.map { if (it.formatId == "248") it.copy(tbr = 9000.0) else it })
        assertEquals(110_000_000L, buildQualityOptions(info, MediaType.VIDEO).at(1080).estBytes)
    }

    @Test fun `prefers a stream with a known size over a sizeless (HLS) one of the same codec`() {
        val info = YT_4K.copy(formats = YT_4K.formats + f("96", "mp4", 1080, "avc1.640028", "none", null, null, 9000.0))
        assertEquals(110_000_000L, buildQualityOptions(info, MediaType.VIDEO).at(1080).estBytes)
    }

    @Test fun `still offers Best when no heights are known`() = assertEquals(
        listOf(QualityOption(null, "⭐ Best", null)),
        buildQualityOptions(MUXED_ONLY.copy(formats = emptyList()), MediaType.VIDEO)
    )

    private val videoChoices = listOf(
        VideoChoice(MediaType.VIDEO, null), VideoChoice(MediaType.VIDEO, 1080), VideoChoice(MediaType.VIDEO, 360)
    )

    @Test fun `never selects a video-only stream without audio`() {
        for (c in videoChoices) {
            val args = buildFormatSelector(c)
            assertTrue(args[args.indexOf("-f") + 1].contains("+ba"))
            assertEquals("mp4", args[args.indexOf("--merge-output-format") + 1])
        }
    }

    @Test fun `prefers widely playable H264 video and m4a audio at the chosen resolution`() {
        for (c in videoChoices) {
            val args = buildFormatSelector(c)
            assertEquals("res,proto,+codec:avc:m4a", args[args.indexOf("-S") + 1])
        }
    }

    @Test fun `caps the height for a chosen quality`() = assertEquals(
        listOf("-f", "bv*[height<=720]+ba/b[height<=720]/bv*+ba/b", "-S", "res,proto,+codec:avc:m4a", "--merge-output-format", "mp4"),
        buildFormatSelector(VideoChoice(MediaType.VIDEO, 720))
    )

    @Test fun `extracts mp3 and m4a audio`() {
        assertEquals(
            listOf("-f", "ba/b", "-x", "--audio-format", "mp3", "--audio-quality", "0"),
            buildFormatSelector(VideoChoice(MediaType.AUDIO, null, AudioFormat.MP3))
        )
        assertEquals(
            listOf("-f", "ba[ext=m4a]/ba/b", "-x", "--audio-format", "m4a"),
            buildFormatSelector(VideoChoice(MediaType.AUDIO, null, AudioFormat.M4A))
        )
    }

    @Test fun `preset qualities offer common heights and audio before the real list is known`() {
        val (video, audio) = presetQualityOptions()
        assertEquals(listOf("⭐ Best", "1080p", "720p", "480p", "360p"), video.map { it.label })
        assertEquals(listOf(null, 1080, 720, 480, 360), video.map { it.height })
        assertEquals(listOf("MP3", "M4A"), audio.map { it.label })
        assertTrue((video + audio).all { it.estBytes == null })
    }
}
