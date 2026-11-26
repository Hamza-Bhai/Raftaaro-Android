package io.github.hamzabhai.raftaaro.engine

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Runs the programs youtubedl-android bundles (Python, ffmpeg) directly, with the environment the
 * library gives them. Valid once [YtDlpEngine.ensureReady] has unpacked them.
 */
class Bundled(context: Context) {
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    private val home = File(context.noBackupFilesDir, "youtubedl-android")
    private val packages = File(home, "packages")
    private val tmp = context.cacheDir

    /** The yt-dlp zip the library runs. */
    val ytDlp = File(home, "yt-dlp/yt-dlp")

    /**
     * Runs `lib<name>.so` with `args`; on cancel kills it. Returns its output (stdout and stderr);
     * throws [IOException] with the last of it when it fails.
     */
    suspend fun run(name: String, args: List<String>): String = withContext(Dispatchers.IO) {
        val pb = ProcessBuilder(listOf(File(nativeDir, "lib$name.so").absolutePath) + args).redirectErrorStream(true)
        pb.environment().apply {
            put("LD_LIBRARY_PATH", listOf("python", "ffmpeg").joinToString(":") { File(packages, "$it/usr/lib").absolutePath })
            put("PYTHONHOME", File(packages, "python/usr").absolutePath)
            put("SSL_CERT_FILE", File(packages, "python/usr/etc/tls/cert.pem").absolutePath)
            put("HOME", home.absolutePath)
            put("TMPDIR", tmp.absolutePath)
        }
        coroutineScope {
            val p = pb.start()
            // the reads below block: a cancel kills the process, which ends them
            val watcher = launch {
                try {
                    awaitCancellation()
                } finally {
                    p.destroy()
                }
            }
            try {
                val out = p.inputStream.bufferedReader().readText()
                val code = p.waitFor()
                coroutineContext.ensureActive()
                if (code != 0) throw IOException("$name failed ($code): ${out.takeLast(500)}")
                out
            } finally {
                watcher.cancel()
                p.destroy()
            }
        }
    }
}
