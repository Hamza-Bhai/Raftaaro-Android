package io.github.hamzabhai.raftaaro.core

enum class JobKind { FILE, VIDEO }

enum class JobStatus { QUEUED, PROBING, DOWNLOADING, MERGING, PAUSED, DONE, ERROR }

enum class MediaType { VIDEO, AUDIO }

enum class AudioFormat { MP3, M4A }

/** What to download from a video page. `height == null` means the best available. */
data class VideoChoice(val media: MediaType, val height: Int?, val audioFormat: AudioFormat? = null)

data class Job(
    val id: String,
    val kind: JobKind,
    val url: String,
    val title: String,
    val thumbnail: String? = null,
    val choice: VideoChoice? = null,
    /** Playlist subfolder name, already sanitised. */
    val folder: String? = null,
    val status: JobStatus = JobStatus.QUEUED,
    val totalBytes: Long? = null,
    val receivedBytes: Long = 0,
    val speedBps: Long = 0,
    val etaSec: Long? = null,
    val error: String? = null,
    /** Automatic retries used after YouTube rate limiting. */
    val attempts: Int = 0,
    /** When a rate-limited job may start again (epoch ms). */
    val retryAt: Long? = null,
    val createdAt: Long = 0,
    /** Content URI of the finished file. */
    val savedUri: String? = null,
    val fileName: String? = null
)

data class YtFormat(
    val formatId: String,
    val ext: String,
    val height: Int?,
    val vcodec: String,
    val acodec: String,
    val filesize: Long?,
    val filesizeApprox: Long?,
    /** Total bitrate in kbit/s. */
    val tbr: Double?
)

data class VideoInfo(
    val url: String,
    val title: String,
    val durationSec: Int?,
    val uploader: String?,
    val thumbnail: String?,
    val formats: List<YtFormat>
)

data class PlaylistEntry(val url: String, val title: String, val durationSec: Int?)

data class PlaylistInfo(val url: String, val title: String, val entries: List<PlaylistEntry>)

/** A row in the quality picker. `height == null` is "Best" (or audio). */
data class QualityOption(val height: Int?, val label: String, val estBytes: Long?, val audioFormat: AudioFormat? = null)

data class Settings(
    /** null = default Download/Raftaaro/<bucket>; otherwise a SAF tree URI. */
    val videosDir: String? = null,
    val audioDir: String? = null,
    val filesDir: String? = null,
    val partsPerFile: Int = 8,
    val maxConcurrent: Int = 3,
    val useSignIns: Boolean = true,
    val clipboardPrompt: Boolean = true,
    val lastYtDlpUpdate: Long? = null,
    val batteryTipShown: Boolean = false
)
