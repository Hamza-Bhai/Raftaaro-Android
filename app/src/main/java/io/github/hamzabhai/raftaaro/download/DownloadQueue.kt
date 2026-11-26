package io.github.hamzabhai.raftaaro.download

import android.content.Intent
import androidx.core.content.ContextCompat
import io.github.hamzabhai.raftaaro.AppGraph
import io.github.hamzabhai.raftaaro.core.Job
import io.github.hamzabhai.raftaaro.core.JobKind
import io.github.hamzabhai.raftaaro.core.JobStatus
import io.github.hamzabhai.raftaaro.core.VideoChoice
import io.github.hamzabhai.raftaaro.core.afterFailure
import io.github.hamzabhai.raftaaro.core.jobsToStart
import io.github.hamzabhai.raftaaro.core.nextWake
import io.github.hamzabhai.raftaaro.core.onAppStart
import io.github.hamzabhai.raftaaro.http.HttpDownload
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job as CoJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/** States in which a job keeps the download service alive. */
val LIVE_STATUSES = setOf(JobStatus.QUEUED, JobStatus.PROBING, JobStatus.DOWNLOADING, JobStatus.MERGING)

private const val PERSIST_MS = 1000L

/** Runs downloads: starts queued jobs by the queue rules, writes progress, saves finished files. */
class DownloadQueue(private val graph: AppGraph) {
    private val repo get() = graph.jobs
    private val lock = Mutex()
    private val running = mutableMapOf<String, CoJob>()
    private var wake: CoJob? = null
    /** True once interrupted jobs from a previous process were marked paused. */
    private val started = CompletableDeferred<Unit>()
    /** True when downloads were running as the app was last killed, for the main screen's battery tip. */
    val interruptedOnStart = MutableStateFlow(false)

    init {
        graph.scope.launch {
            val all = repo.all()
            val fixed = onAppStart(all)
            if (fixed != all) {
                repo.updateAll(fixed)
                interruptedOnStart.value = true
            }
            started.complete(Unit)
            pump()
        }
    }

    suspend fun enqueue(url: String, title: String, kind: JobKind, choice: VideoChoice?, folder: String?, thumbnail: String?, totalBytes: Long? = null): String {
        started.await()
        val job = Job(
            id = UUID.randomUUID().toString(), kind = kind, url = url, title = title, thumbnail = thumbnail,
            choice = choice, folder = folder, totalBytes = totalBytes, createdAt = System.currentTimeMillis()
        )
        repo.add(job)
        startService()
        pump()
        return job.id
    }

    suspend fun pause(id: String) {
        stop(id)
        repo.get(id)?.takeIf { it.status != JobStatus.DONE }?.let {
            repo.update(it.copy(status = JobStatus.PAUSED, speedBps = 0, etaSec = null))
        }
        pump()
    }

    suspend fun resume(id: String) {
        val job = repo.get(id) ?: return
        if (job.status != JobStatus.PAUSED && job.status != JobStatus.ERROR) return
        repo.update(job.copy(status = JobStatus.QUEUED, error = null, attempts = 0, retryAt = null))
        startService()
        pump()
    }

    suspend fun retry(id: String) = resume(id)

    /** Removes the job; `deleteFile` also deletes the saved file. Partial data is always deleted. */
    suspend fun remove(id: String, deleteFile: Boolean) {
        stop(id)
        val job = repo.get(id) ?: return
        if (deleteFile) job.savedUri?.let { graph.saver.delete(it) }
        graph.workDir(id).deleteRecursively()
        repo.delete(id)
        pump()
    }

    /** Pauses everything running or waiting, e.g. when Android's background limit ends the service. */
    suspend fun pauseAll(reason: String?) {
        val ids = lock.withLock { running.keys.toList() }
        ids.forEach { stop(it) }
        repo.updateAll(repo.all().filter { it.status in LIVE_STATUSES }.map {
            it.copy(status = JobStatus.PAUSED, speedBps = 0, etaSec = null, error = reason)
        })
    }

    private suspend fun stop(id: String) {
        val job = lock.withLock { running.remove(id) }
        job?.let {
            it.cancel()
            it.join()
        }
    }

    /**
     * Keeps the process in the foreground while anything runs. Android 12+ refuses this from the
     * background (e.g. a cooldown timer firing); the download then runs unprotected until the app
     * is opened again, rather than failing.
     */
    private fun startService() {
        runCatching { ContextCompat.startForegroundService(graph.context, Intent(graph.context, DownloadService::class.java)) }
    }

    /** Starts whatever the rules allow now, and sets a timer for the next rate-limit cooldown. */
    suspend fun pump() {
        started.await()
        lock.withLock {
            val jobs = repo.all()
            val now = System.currentTimeMillis()
            val max = graph.settings.current().maxConcurrent
            val toStart = jobsToStart(jobs, running.keys, max, now)
            if (toStart.isNotEmpty()) startService()
            for (id in toStart) {
                val job = jobs.first { it.id == id }
                running[id] = graph.scope.launch { run(job) }
            }
            wake?.cancel()
            wake = nextWake(jobs, now)?.let { at ->
                graph.scope.launch {
                    delay(at - System.currentTimeMillis() + 100)
                    pump()
                }
            }
        }
    }

    /** Latest progress from a runner, written to the database at most once a second. */
    private data class Tick(val received: Long, val total: Long?, val speed: Long, val merging: Boolean = false)

    private suspend fun run(start: Job) {
        var job = start.copy(status = JobStatus.DOWNLOADING, speedBps = 0, etaSec = null, error = null)
        repo.update(job)
        val settings = graph.settings.current()
        val work = graph.workDir(job.id)
        try {
            val ticks = MutableStateFlow<Tick?>(null)
            val file = coroutineScope {
                // a child, so pausing (cancelling this job) also stops it before the job is marked paused
                val writer = launch {
                    var last = 0L
                    ticks.collect { t ->
                        if (t == null) return@collect
                        val now = System.currentTimeMillis()
                        if (!t.merging && now - last < PERSIST_MS) return@collect
                        last = now
                        val eta = if (t.total != null && t.speed > 0) (t.total - t.received).coerceAtLeast(0) / t.speed else null
                        job = job.copy(
                            status = if (t.merging) JobStatus.MERGING else JobStatus.DOWNLOADING,
                            receivedBytes = t.received, totalBytes = t.total ?: job.totalBytes, speedBps = t.speed, etaSec = eta
                        )
                        repo.update(job)
                    }
                }
                try {
                    if (job.kind == JobKind.FILE) {
                        HttpDownload(graph.http, job.url, work, settings.partsPerFile)
                            .run { ticks.value = Tick(it.received, it.total, it.speedBps) }.file
                    } else {
                        val cookies = graph.cookiesFor(job.url, settings)
                        graph.engine.download(
                            job.id, job.url, job.choice, work, cookies, job.totalBytes,
                            { ticks.value = Tick(it.received, it.total, it.speedBps, ticks.value?.merging == true) },
                            { ticks.value = (ticks.value ?: Tick(0, null, 0)).copy(speed = 0, merging = true) },
                            // playlist entries go one after another: pace them; a single tap starts at once
                            paced = job.folder != null,
                            parts = settings.partsPerFile
                        )
                    }
                } finally {
                    withContext(NonCancellable) { writer.cancelAndJoin() }
                }
            }
            val saved = graph.saver.save(file, bucketOf(job.kind, job.choice), job.folder, settings)
            val size = runCatching {
                graph.context.contentResolver.openAssetFileDescriptor(Uri.parse(saved.uri), "r")?.use { it.length }
            }.getOrNull()?.takeIf { it > 0 } ?: job.totalBytes
            job = job.copy(
                status = JobStatus.DONE, savedUri = saved.uri, fileName = saved.name, error = null,
                receivedBytes = size ?: job.receivedBytes, totalBytes = size, speedBps = 0, etaSec = null
            )
            repo.update(job)
            work.deleteRecursively()
            Notifications.finished(graph.context, job)
        } catch (e: CancellationException) {
            throw e // paused or removed: whoever cancelled sets the state
        } catch (e: Throwable) {
            repo.update(afterFailure(job, e.message ?: e.javaClass.simpleName, System.currentTimeMillis()))
        }
        lock.withLock { running.remove(start.id) }
        graph.scope.launch { pump() }
    }
}
