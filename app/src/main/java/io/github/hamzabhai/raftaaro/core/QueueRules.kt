package io.github.hamzabhai.raftaaro.core

private const val MAX_YOUTUBE = 1
private const val RATE_LIMIT_COOLDOWN_MS = 10 * 60 * 1000L
private const val MAX_AUTO_RETRIES = 2

private val ACTIVE = setOf(JobStatus.PROBING, JobStatus.DOWNLOADING, JobStatus.MERGING)

/**
 * Ids of queued jobs to start now, oldest first. At most `maxConcurrent` run at once, and YouTube
 * jobs run one at a time and not at all while a rate-limited job is cooling down, so the user's
 * network is not flagged.
 */
fun jobsToStart(jobs: List<Job>, runningIds: Set<String>, maxConcurrent: Int, now: Long): List<String> {
    val coolingUntil = jobs.filter { it.status == JobStatus.QUEUED }.maxOfOrNull { it.retryAt ?: 0 } ?: 0
    var youtubeRunning = jobs.count { it.id in runningIds && isYouTubeUrl(it.url) }
    var free = maxConcurrent - runningIds.size
    val start = mutableListOf<String>()
    for (job in jobs.sortedBy { it.createdAt }) {
        if (free <= 0) break
        if (job.status != JobStatus.QUEUED || job.id in runningIds) continue
        if (isYouTubeUrl(job.url)) {
            if (youtubeRunning >= MAX_YOUTUBE || coolingUntil > now) continue
            youtubeRunning++
        }
        start += job.id
        free--
    }
    return start
}

/** Next state of a job whose runner failed with `message`: back off on rate limits, else an error. */
fun afterFailure(job: Job, message: String, now: Long): Job {
    val attempts = job.attempts + 1
    if (message == RATE_LIMIT_MESSAGE && attempts <= MAX_AUTO_RETRIES) {
        return job.copy(status = JobStatus.QUEUED, attempts = attempts, retryAt = now + RATE_LIMIT_COOLDOWN_MS, speedBps = 0, etaSec = null, error = message)
    }
    return job.copy(status = JobStatus.ERROR, error = message, speedBps = 0, etaSec = null)
}

/** On app/service start, downloads that were running when the process died are paused. */
fun onAppStart(jobs: List<Job>): List<Job> =
    jobs.map { if (it.status in ACTIVE) it.copy(status = JobStatus.PAUSED, speedBps = 0, etaSec = null) else it }

/** Earliest future retryAt among queued jobs, for the wake-up timer. */
fun nextWake(jobs: List<Job>, now: Long): Long? =
    jobs.filter { it.status == JobStatus.QUEUED }.mapNotNull { it.retryAt }.filter { it > now }.minOrNull()

/**
 * While a YouTube playlist video downloads, the page of the next one (the oldest queued YouTube
 * playlist video) is read, so it can start its direct download at once. Null for anything else.
 */
fun readAheadFor(job: Job, jobs: List<Job>): String? {
    fun playlistYouTube(j: Job) = j.kind == JobKind.VIDEO && j.folder != null && isYouTubeUrl(j.url)
    if (!playlistYouTube(job)) return null
    return jobs.filter { it.id != job.id && it.status == JobStatus.QUEUED && playlistYouTube(it) }
        .minByOrNull { it.createdAt }?.url
}

/** When the next paced YouTube page read may start: at once the first time, else `gapMs` after the last one ended. */
fun nextReadAt(lastReadEnd: Long?, now: Long, gapMs: Long): Long =
    if (lastReadEnd == null) now else maxOf(now, lastReadEnd + gapMs)
