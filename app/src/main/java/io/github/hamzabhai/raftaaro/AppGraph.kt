package io.github.hamzabhai.raftaaro

import android.content.Context
import io.github.hamzabhai.raftaaro.core.Site
import io.github.hamzabhai.raftaaro.data.AppDatabase
import io.github.hamzabhai.raftaaro.data.JobRepository
import io.github.hamzabhai.raftaaro.data.SettingsStore
import io.github.hamzabhai.raftaaro.download.DownloadQueue
import io.github.hamzabhai.raftaaro.download.Saver
import io.github.hamzabhai.raftaaro.engine.YtDlpEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Everything the app shares, built once in [App]. */
class AppGraph(context: Context) {
    val context: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val jobs = JobRepository(AppDatabase.open(this.context).jobDao())
    val settings = SettingsStore(this.context)
    val engine = YtDlpEngine(this.context)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    val saver = Saver(this.context)
    val queue = DownloadQueue(this)

    /** Where a site's sign-in cookies are kept (only on this phone). */
    fun cookieFile(site: Site): File = File(this.context.filesDir, "cookies/${site.name.lowercase()}.txt")

    /** Per-job folder for partial data; survives restarts so downloads can resume. */
    fun workDir(jobId: String): File = File(this.context.filesDir, "jobs/$jobId")
}
