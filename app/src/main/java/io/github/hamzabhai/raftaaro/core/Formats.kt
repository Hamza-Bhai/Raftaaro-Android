package io.github.hamzabhai.raftaaro.core

private val HEIGHT_NAMES = mapOf(4320 to "8K", 2160 to "4K", 1440 to "2K")

/** Known size, else bitrate (kbit/s) x duration. */
private fun sizeOf(f: YtFormat, durationSec: Int?): Long? =
    f.filesize ?: f.filesizeApprox ?: if (f.tbr != null && durationSec != null) Math.round(f.tbr * 1000 * durationSec / 8) else null

/** Mirrors the -S codec preference: H.264 first, then H.265, VP9, AV1, anything else. */
private fun codecRank(vcodec: String): Int {
    val order = listOf(Regex("^(avc|h264)"), Regex("^(hev|hvc|h265)"), Regex("^vp0?9"), Regex("^av0?1"))
    val i = order.indexOfFirst { it.containsMatchIn(vcodec) }
    return if (i == -1) order.size else i
}

private fun YtFormat.hasVideo() = vcodec.isNotEmpty() && vcodec != "none"
private fun YtFormat.hasAudio() = acodec.isNotEmpty() && acodec != "none"

private fun heightLabel(h: Int) = HEIGHT_NAMES[h]?.let { "${h}p ($it)" } ?: "${h}p"

private fun largestAudioSize(formats: List<YtFormat>, durationSec: Int?): Long? =
    formats.filter { it.hasAudio() && !it.hasVideo() }.mapNotNull { sizeOf(it, durationSec) }.maxOrNull()

/** Size of what yt-dlp will produce for this height: the preferred-codec video there, plus audio if separate. */
private fun estimateForHeight(formats: List<YtFormat>, height: Int, durationSec: Int?): Long? {
    val pick = formats.filter { it.hasVideo() && it.height == height }.sortedWith(
        // sized formats are plain https downloads, which -S proto puts before sizeless HLS ones
        compareByDescending<YtFormat> { sizeOf(it, null) != null }
            .thenBy { codecRank(it.vcodec) }
            .thenByDescending { it.tbr ?: 0.0 }
    ).firstOrNull() ?: return null
    val video = sizeOf(pick, durationSec) ?: return null
    if (pick.hasAudio()) return video
    val audio = largestAudioSize(formats, durationSec) ?: return null
    return video + audio
}

/** Rows for the quality picker. Only heights that really exist are offered. */
fun buildQualityOptions(info: VideoInfo, media: MediaType): List<QualityOption> {
    if (media == MediaType.AUDIO) {
        val est = largestAudioSize(info.formats, info.durationSec)
        return listOf(
            QualityOption(null, "MP3", est, AudioFormat.MP3),
            QualityOption(null, "M4A", est, AudioFormat.M4A)
        )
    }
    val heights = info.formats.filter { it.hasVideo() }.mapNotNull { it.height }.filter { it > 0 }.distinct().sortedDescending()
    val options = heights.map { QualityOption(it, heightLabel(it), estimateForHeight(info.formats, it, info.durationSec)) }
    val top = options.firstOrNull()
    val best = QualityOption(null, if (top != null) "⭐ Best (${top.label})" else "⭐ Best", top?.estBytes)
    return listOf(best) + options
}

/** yt-dlp arguments selecting the formats for a choice. Video always includes an audio stream. */
fun buildFormatSelector(choice: VideoChoice): List<String> {
    if (choice.media == MediaType.AUDIO) {
        return if (choice.audioFormat == AudioFormat.M4A) listOf("-f", "ba[ext=m4a]/ba/b", "-x", "--audio-format", "m4a")
        else listOf("-f", "ba/b", "-x", "--audio-format", "mp3", "--audio-quality", "0")
    }
    // proto: at the same height a plain https download (exact size) beats a streamed m3u8 one
    val merge = listOf("-S", "res,proto,+codec:avc:m4a", "--merge-output-format", "mp4")
    val h = choice.height ?: return listOf("-f", "bv*+ba/b") + merge
    return listOf("-f", "bv*[height<=$h]+ba/b[height<=$h]/bv*+ba/b") + merge
}

/**
 * Rows shown while the real list is still being read (YouTube takes half a minute on a phone).
 * Heights are maximums: yt-dlp picks the best stream at or below them when downloading. Best has
 * no cap (4K, 2K…), which its label says, since the rows below it stop at 1080p.
 */
fun presetQualityOptions(): Pair<List<QualityOption>, List<QualityOption>> = Pair(
    listOf(QualityOption(null, "⭐ Best (highest available)", null)) + listOf(1080, 720, 480, 360).map { QualityOption(it, "${it}p", null) },
    listOf(QualityOption(null, "MP3", null, AudioFormat.MP3), QualityOption(null, "M4A", null, AudioFormat.M4A))
)
