package io.github.hamzabhai.raftaaro.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** One stream to fetch with plain HTTP: its address, the headers yt-dlp would send, and its size if known. */
data class DirectStream(val url: String, val ext: String, val size: Long?, val headers: Map<String, String>)

/** What ffmpeg does with the fetched streams. */
enum class DirectOutput { MERGE_MP4, REMUX_MP4, REMUX_M4A, MP3 }

/** A download that skips yt-dlp: the streams yt-dlp would pick, fetched directly. */
data class DirectPlan(val video: DirectStream?, val audio: DirectStream?, val output: DirectOutput, val fileName: String) {
    val streams: List<DirectStream> get() = listOfNotNull(video, audio)
}

private val json = Json { ignoreUnknownKeys = true }

private data class Fmt(
    val stream: DirectStream, val height: Int?, val vcodec: String, val acodec: String,
    val plain: Boolean, val tbr: Double, val abr: Double
) {
    val hasVideo get() = vcodec != "none"
    val hasAudio get() = acodec != "none"
}

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull

private fun parse(o: JsonObject): Fmt? {
    val url = o.str("url") ?: return null
    val protocol = o.str("protocol").orEmpty()
    val headers = (o["http_headers"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }?.toMap().orEmpty()
    val size = (o.num("filesize") ?: o.num("filesize_approx"))?.toLong()
    return Fmt(
        DirectStream(url, o.str("ext").orEmpty(), size, headers),
        o.num("height")?.toInt(), o.str("vcodec") ?: "none", o.str("acodec") ?: "none",
        // a single file over http(s); streamed (m3u8, dash fragments) formats need yt-dlp
        plain = (protocol == "https" || protocol == "http") && o["fragments"] == null,
        tbr = o.num("tbr") ?: 0.0, abr = o.num("abr") ?: o.num("tbr") ?: 0.0
    )
}

/** Same order as Formats.codecRank, the -S "+codec:avc" preference. */
private fun codecRank(vcodec: String): Int {
    val order = listOf(Regex("^(avc|h264)"), Regex("^(hev|hvc|h265)"), Regex("^vp0?9"), Regex("^av0?1"))
    val i = order.indexOfFirst { it.containsMatchIn(vcodec) }
    return if (i == -1) order.size else i
}

/** -S "res,proto,+codec:avc:m4a": height, then plain downloads, then H.264, then bitrate. */
private val videoOrder = compareByDescending<Fmt> { it.height ?: 0 }
    .thenByDescending { it.plain }
    .thenBy { codecRank(it.vcodec) }
    .thenByDescending { it.tbr }

/** The audio half of a merge under the same -S: plain downloads, m4a (AAC) first, then bitrate. */
private val mergeAudioOrder = compareByDescending<Fmt> { it.plain }
    .thenByDescending { it.acodec.startsWith("mp4a") }
    .thenByDescending { it.abr }

/** `-f ba` without -S (the audio choices): plain downloads, then bitrate. */
private val audioOrder = compareByDescending<Fmt> { it.plain }.thenByDescending { it.abr }

/** UTF-8 prefix of at most `max` bytes that does not split a character (yt-dlp's `%(title).150B`). */
private fun takeBytes(s: String, max: Int): String {
    var bytes = 0
    var end = 0
    while (end < s.length) {
        val cp = s.codePointAt(end)
        val n = String(Character.toChars(cp)).toByteArray().size
        if (bytes + n > max) break
        bytes += n
        end += Character.charCount(cp)
    }
    return s.substring(0, end)
}

/**
 * The streams yt-dlp's selector ([buildFormatSelector]) would pick from a saved reading, when every
 * one is a plain HTTP download; null whenever yt-dlp should do the download itself.
 */
fun planDirect(infoJson: String, choice: VideoChoice?): DirectPlan? {
    if (choice == null) return null
    val root = runCatching { json.parseToJsonElement(infoJson) as JsonObject }.getOrNull() ?: return null
    val formats = (root["formats"] as? JsonArray)?.filterIsInstance<JsonObject>()?.mapNotNull(::parse).orEmpty()
    if (formats.isEmpty()) return null
    val audioOnly = formats.filter { it.hasAudio && !it.hasVideo }

    val video: Fmt?
    val audio: Fmt?
    val output: DirectOutput
    if (choice.media == MediaType.AUDIO) {
        video = null
        if (choice.audioFormat == AudioFormat.M4A) {
            audio = audioOnly.filter { it.stream.ext == "m4a" }.sortedWith(audioOrder).firstOrNull() ?: return null
            output = DirectOutput.REMUX_M4A
        } else {
            audio = audioOnly.sortedWith(audioOrder).firstOrNull() ?: return null
            output = DirectOutput.MP3
        }
    } else {
        // "bv*[height<=h]+ba / ... / bv*+ba": within the cap if anything fits, else the best there is
        val withVideo = formats.filter { it.hasVideo }
        val h = choice.height
        val fits = if (h == null) withVideo else withVideo.filter { (it.height ?: 0) <= h }
        video = (fits.ifEmpty { withVideo }).sortedWith(videoOrder).firstOrNull() ?: return null
        if (video.hasAudio) {
            audio = null
            output = DirectOutput.REMUX_MP4
        } else {
            audio = audioOnly.sortedWith(mergeAudioOrder).firstOrNull() ?: return null
            output = DirectOutput.MERGE_MP4
        }
    }
    if (video?.plain == false || audio?.plain == false) return null

    val ext = when (output) {
        DirectOutput.MERGE_MP4, DirectOutput.REMUX_MP4 -> "mp4"
        DirectOutput.REMUX_M4A -> "m4a"
        DirectOutput.MP3 -> "mp3"
    }
    // same name yt-dlp writes: "%(title).150B [%(id)s] 720p.%(ext)s"
    val tag = if (choice.media == MediaType.VIDEO && choice.height != null) " ${choice.height}p" else ""
    val title = takeBytes(root.str("title") ?: "video", 150)
    val id = root.str("id")?.let { " [$it]" }.orEmpty()
    return DirectPlan(video?.stream, audio?.stream, output, sanitizeFileName("$title$id$tag.$ext"))
}
