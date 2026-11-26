package io.github.hamzabhai.raftaaro

import android.content.Context
import io.github.hamzabhai.raftaaro.core.Settings
import io.github.hamzabhai.raftaaro.core.Site
import io.github.hamzabhai.raftaaro.core.cookieCount
import io.github.hamzabhai.raftaaro.core.usesSiteLogin
import io.github.hamzabhai.raftaaro.data.AppDatabase
import io.github.hamzabhai.raftaaro.data.JobRepository
import io.github.hamzabhai.raftaaro.data.SettingsStore
import io.github.hamzabhai.raftaaro.download.DownloadQueue
import io.github.hamzabhai.raftaaro.download.Saver
import io.github.hamzabhai.raftaaro.engine.YtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Everything the app shares, built once in [App]. */
class AppGraph(context: Context) {
    val context: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val jobs = JobRepository(AppDatabase.open(this.context).jobDao())
    val settings = SettingsStore(this.context)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        // same plain name the desktop app sends
        .addInterceptor { chain ->
            val req = chain.request()
            chain.proceed(if (req.header("User-Agent") == null) req else req.newBuilder().header("User-Agent", "Raftaaro").build())
        }
        .build()
    val engine = YtDlpEngine(this.context, scope, http)
    val saver = Saver(this.context)
    val queue = DownloadQueue(this)

    /** Unpacks the engine and checks for a newer yt-dlp (at most daily) as soon as the app starts. */
    var warmUpJob: Job? = null
        private set

    fun warmUp() {
        val job = scope.launch {
            runCatching { engine.ensureReady() }
            runCatching { engine.updateIfDue(settings) }
        }
        warmUpJob = job
        // after any update (a new yt-dlp needs compiling again); not part of warmUpJob, which probes wait for
        scope.launch {
            job.join()
            runCatching { engine.precompileIfNeeded() }
        }
    }

    /**
     * Before the first YouTube probe ever, give a running yt-dlp update up to 8 s to finish:
     * the bundled yt-dlp is old and YouTube rejects old versions.
     */
    suspend fun awaitFirstUpdate() {
        if (settings.current().lastYtDlpUpdate != null) return
        withTimeoutOrNull(8_000) { warmUpJob?.join() }
    }

    /** Where a site's sign-in cookies are kept (only on this phone). */
    fun cookieFile(site: Site): File = File(this.context.filesDir, "cookies/${site.name.lowercase()}.txt")

    /** The saved sign-in for this URL's site, when sign-ins are on and the user is signed in. */
    fun cookiesFor(url: String, settings: Settings): File? =
        usesSiteLogin(url)?.let { cookieFile(it) }
            ?.takeIf { settings.useSignIns && it.isFile && cookieCount(it.readText()) > 0 }

    /** Per-job folder for partial data; survives restarts so downloads can resume. */
    fun workDir(jobId: String): File = File(this.context.filesDir, "jobs/$jobId")
}
