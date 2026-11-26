package io.github.hamzabhai.raftaaro.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlin.math.max
import kotlin.math.roundToLong

/** A failure from yt-dlp, already turned into a short message for the user. */
class YtDlpError(message: String) : Exception(message)

const val RATE_LIMIT_MESSAGE = "YouTube asked us to slow down. Raftaaro will retry automatically."
const val OUTDATED_MESSAGE = "yt-dlp is out of date — update it in Settings"
/** yt-dlp's ffmpeg cannot record YouTube's live HLS on a phone, and a recording would never end. */
const val LIVE_MESSAGE = "This is a live stream — download it after it ends"
const val UNSUPPORTED_MESSAGE = "Raftaaro can't download from this site"
const val SIGN_IN_MESSAGE = "Sign-in required — sign in to this site in Raftaaro's Settings, then try again"

/** Printed for every progress tick; parsed by [parseProgress]. */
const val PROGRESS_TEMPLATE =
    "RFTP %(progress.downloaded_bytes)s %(progress.total_bytes)s %(progress.total_bytes_estimate)s %(progress.speed)s %(progress.eta)s"
/** Printed before downloading: the size of every chosen format together (video + audio). */
const val SIZE_MARK = "RFTSIZE "
/** Printed after the final move: the finished file's path. */
const val FILE_MARK = "RFTFILE:"

data class YtProgress(val downloaded: Long, val totalBytes: Long?, val estimated: Boolean, val speedBps: Long?, val etaSec: Long?)

private fun num(s: String?): Long? = s?.toDoubleOrNull()?.takeIf { it.isFinite() }?.roundToLong()

fun parseProgress(line: String): YtProgress? {
    if (!line.startsWith("RFTP ")) return null
    val p = line.trim().split(Regex("\\s+"))
    val total = num(p.getOrNull(2))
    val estimate = num(p.getOrNull(3))
    return YtProgress(num(p.getOrNull(1)) ?: 0, total ?: estimate, total == null && estimate != null, num(p.getOrNull(4)), num(p.getOrNull(5)))
}

private val RATE_LIMIT_ERROR = Regex("HTTP Error 429|Too Many Requests|not a bot|rate.?limit", RegexOption.IGNORE_CASE)

fun classifyYtDlpError(stderr: String): String {
    if (RATE_LIMIT_ERROR.containsMatchIn(stderr) && !Regex("private video|login required", RegexOption.IGNORE_CASE).containsMatchIn(stderr)) {
        return RATE_LIMIT_MESSAGE
    }
    if (Regex("sign in|login required|private video|log in", RegexOption.IGNORE_CASE).containsMatchIn(stderr)) return SIGN_IN_MESSAGE
    if (Regex("HTTP Error 403|Unable to extract|nsig|Requested format is not available", RegexOption.IGNORE_CASE).containsMatchIn(stderr)) {
        return OUTDATED_MESSAGE
    }
    if (Regex("Unsupported URL", RegexOption.IGNORE_CASE).containsMatchIn(stderr)) return UNSUPPORTED_MESSAGE
    if (Regex("Video unavailable|This video is not available", RegexOption.IGNORE_CASE).containsMatchIn(stderr)) return "Video unavailable"
    val last = stderr.lines().lastOrNull { it.startsWith("ERROR:") } ?: return "Download failed"
    return last.removePrefix("ERROR:").trim()
}

sealed interface ProbeResult {
    data class Video(val info: VideoInfo) : ProbeResult
    data class Playlist(val info: PlaylistInfo) : ProbeResult
    /** A channel root: probe this tab (its Videos tab) instead. */
    data class OpenTab(val url: String) : ProbeResult
}

private val json = Json { ignoreUnknownKeys = true }

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
private fun JsonObject.dbl(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
private fun JsonObject.long(key: String): Long? = dbl(key)?.roundToLong()
private fun JsonObject.int(key: String): Int? = dbl(key)?.roundToLong()?.toInt()
private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
private fun JsonObject.list(key: String): List<JsonObject> = (this[key] as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()

private fun entryUrl(e: JsonObject): String {
    val url = e.str("url")
    if (url != null && url.startsWith("http")) return url
    return "https://www.youtube.com/watch?v=${e.str("id") ?: url ?: ""}"
}

/** Turn `yt-dlp -J --flat-playlist` output into a video, a playlist, or a channel tab to open. */
fun parseProbe(url: String, output: String): ProbeResult {
    val raw: JsonObject = try {
        json.parseToJsonElement(output) as JsonObject
    } catch (_: Exception) {
        throw YtDlpError("Could not read the answer from yt-dlp")
    }
    if (raw.str("_type") == "playlist") {
        val entries = raw.list("entries")
        if (entries.isNotEmpty() && entries.all { it.str("ie_key") == "YoutubeTab" }) {
            val tab = entries.firstOrNull { it.str("url")?.endsWith("/videos") == true } ?: entries.first()
            return ProbeResult.OpenTab(tab.str("url").orEmpty())
        }
        return ProbeResult.Playlist(
            PlaylistInfo(url, raw.str("title") ?: "Playlist", entries.map { PlaylistEntry(entryUrl(it), it.str("title") ?: "Video", it.int("duration")) })
        )
    }
    if (raw.bool("is_live") == true || raw.str("live_status") in setOf("is_live", "is_upcoming")) throw YtDlpError(LIVE_MESSAGE)
    return ProbeResult.Video(
        VideoInfo(
            url = url,
            title = raw.str("title") ?: "Video",
            durationSec = raw.int("duration"),
            uploader = raw.str("uploader") ?: raw.str("channel"),
            thumbnail = raw.str("thumbnail"),
            formats = raw.list("formats").map {
                YtFormat(
                    formatId = (it["format_id"] as? JsonPrimitive)?.contentOrNull ?: "",
                    ext = it.str("ext") ?: "",
                    height = it.int("height"),
                    vcodec = it.str("vcodec") ?: "none",
                    acodec = it.str("acodec") ?: "none",
                    filesize = it.long("filesize"),
                    filesizeApprox = it.long("filesize_approx"),
                    tbr = it.dbl("tbr")
                )
            }
        )
    )
}

data class VideoProgress(val received: Long, val total: Long?, val speedBps: Long)

private val ALREADY_DOWNLOADED = Regex("""^\[download] (.+) has already been downloaded""")
private val POSTPROCESS = Regex("""^\[(Merger|ExtractAudio|VideoConvertor|FixupM3u8|VideoRemuxer)]""")

private fun pathFromLine(line: String): String? =
    (ALREADY_DOWNLOADED.find(line) ?: Regex("""^\[Merger] Merging formats into "(.+)"$""").find(line)
        ?: Regex("""^\[ExtractAudio] Destination: (.+)$""").find(line))?.groupValues?.get(1)?.trim()

/**
 * Follows one yt-dlp download's output and turns the per-stream progress (video, then audio)
 * into one received/total for the whole job. Port of the desktop's YtDlpDownload.onLine.
 *
 * @param expected size known before starting (the quality picker's estimate), if any.
 */
class ProgressTracker(private var expected: Long?) {
    /** Bytes of streams already finished. */
    private var base = 0L
    private var current: YtProgress? = null
    private var total: Long? = null
    private var emitted = false

    var merging = false
        private set
    /** Files yt-dlp writes, so an unfinished job can be cleaned up. */
    val parts = mutableListOf<String>()
    /** Path from the after_move print. */
    var finalPath: String? = null
        private set
    /** Fallback when yt-dlp skips the after_move print (file already downloaded). */
    var seenPath: String? = null
        private set

    fun onLine(line: String, fileSize: (String) -> Long?): VideoProgress? {
        if (line.startsWith(FILE_MARK)) {
            finalPath = line.removePrefix(FILE_MARK).trim()
            return null
        }
        pathFromLine(line)?.let { seenPath = it }
        if (line.startsWith(SIZE_MARK)) {
            val p = line.removePrefix(SIZE_MARK).trim().split(Regex("\\s+"))
            val fromRate = (p.getOrNull(1)?.toDoubleOrNull() ?: 0.0) * 125 * (p.getOrNull(2)?.toDoubleOrNull() ?: 0.0)
            expected = num(p.getOrNull(0)) ?: if (fromRate > 0) fromRate.roundToLong() else expected
            return null
        }
        if (POSTPROCESS.containsMatchIn(line)) {
            pathFromLine(line)?.let { parts += it }
            merging = true
            return null
        }
        ALREADY_DOWNLOADED.find(line)?.let { m ->
            val path = m.groupValues[1].trim()
            if (Regex("""\.f\d+\.\w+$""").containsMatchIn(path)) parts += path
            base += fileSize(path) ?: 0
            current = null
            return null
        }
        Regex("""^\[download] Destination: (.+)$""").find(line)?.let { m ->
            parts += m.groupValues[1].trim()
            current?.let { base += it.totalBytes ?: it.downloaded }
            current = null
            return idle()
        }
        if (line.startsWith("[download] Sleeping")) return idle()
        val p = parseProgress(line) ?: return null
        current = p
        val received = base + p.downloaded
        // yt-dlp's guess for a streamed format jumps around; only used when nothing better is known
        val streamTotal = if (p.estimated && expected != null) null else p.totalBytes
        val seen = base + (streamTotal ?: p.downloaded)
        total = if (streamTotal != null || expected != null) max(seen, expected ?: 0) else null
        emitted = true
        return VideoProgress(received, total, p.speedBps ?: 0)
    }

    /** Between streams yt-dlp downloads nothing for a few seconds; do not keep showing the old speed. */
    private fun idle(): VideoProgress? =
        if (!emitted) null else VideoProgress(base + (current?.downloaded ?: 0), total, 0)
}
