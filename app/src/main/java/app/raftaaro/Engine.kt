package app.raftaaro

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import java.io.File

/** What the prototype learns about a link before downloading. */
data class LinkInfo(val title: String, val uploader: String?, val durationSec: Int, val heights: List<Int>)

/** Everything the app needs from yt-dlp + ffmpeg, kept in one place (like src/main/video/ytdlp.ts on desktop). */
object Engine {
    private const val PROCESS_ID = "raftaaro-download"
    @Volatile private var ready = false

    /** Unpacks Python, yt-dlp and ffmpeg on first run; takes a few seconds, so call it off the main thread. */
    @Synchronized
    fun init(ctx: Context) {
        if (ready) return
        YoutubeDL.getInstance().init(ctx.applicationContext)
        FFmpeg.getInstance().init(ctx.applicationContext)
        ready = true
    }

    /** The prototype's main question: which yt-dlp and which Python does the bundled engine run? */
    fun check(ctx: Context): String {
        init(ctx)
        val ytdlp = YoutubeDL.getInstance().versionName(ctx) ?: YoutubeDL.getInstance().version(ctx) ?: "?"
        // `yt-dlp -v` with no URL prints its debug header (Python version included), then exits with an error.
        val debug = try {
            val r = YoutubeDL.getInstance().execute(YoutubeDLRequest(emptyList<String>()).addOption("-v"))
            r.err + r.out
        } catch (e: YoutubeDLException) {
            e.message.orEmpty()
        }
        val python = debug.lineSequence().firstOrNull { "Python" in it }?.trim() ?: "Python version not found"
        return "yt-dlp: $ytdlp\n$python"
    }

    fun update(ctx: Context): String {
        init(ctx)
        val status = YoutubeDL.getInstance().updateYoutubeDL(ctx, YoutubeDL.UpdateChannel.STABLE)
        return when (status) {
            YoutubeDL.UpdateStatus.DONE -> "yt-dlp updated to ${YoutubeDL.getInstance().versionName(ctx)}"
            YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "yt-dlp is already up to date"
            null -> "Update gave no answer"
        }
    }

    fun info(ctx: Context, url: String): LinkInfo {
        init(ctx)
        val info = YoutubeDL.getInstance().getInfo(YoutubeDLRequest(url).addOption("--no-playlist"))
        val heights = info.formats.orEmpty().map { it.height }.filter { it > 0 }.distinct().sortedDescending()
        return LinkInfo(info.title ?: "Video", info.uploader, info.duration, heights)
    }

    /**
     * Downloads into a private temp folder, then copies the result to Download/Raftaaro so the
     * user's gallery and file manager see it. Returns the saved file names.
     */
    fun download(
        ctx: Context,
        url: String,
        audioOnly: Boolean,
        onProgress: (percent: Float, etaSec: Long, line: String) -> Unit
    ): List<String> {
        init(ctx)
        val work = File(ctx.cacheDir, "dl-${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val req = YoutubeDLRequest(url)
                .addOption("--no-playlist")
                .addOption("--newline")
                .addOption("-o", "${work.absolutePath}/%(title).120B [%(id)s].%(ext)s")
            if (audioOnly) {
                req.addOption("-x").addOption("--audio-format", "mp3")
            } else {
                req.addOption("-f", "bv*[height<=1080]+ba/b[height<=1080]/b")
                    .addOption("--merge-output-format", "mp4")
            }
            YoutubeDL.getInstance().execute(req, PROCESS_ID) { p, eta, line -> onProgress(p, eta, line) }

            val outputs = work.listFiles().orEmpty().filter {
                it.isFile && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl")
            }
            if (outputs.isEmpty()) error("yt-dlp finished but no file was written")
            return outputs.map { saveToDownloads(ctx, it); it.name }
        } finally {
            work.deleteRecursively()
        }
    }

    fun cancel() {
        YoutubeDL.getInstance().destroyProcessById(PROCESS_ID)
    }

    private fun saveToDownloads(ctx: Context, file: File): Uri {
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension) ?: "application/octet-stream"
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/Raftaaro")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: error("Could not create ${file.name} in Downloads")
        resolver.openOutputStream(uri).use { out ->
            requireNotNull(out) { "Could not open ${file.name} for writing" }
            file.inputStream().use { it.copyTo(out) }
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }
}
