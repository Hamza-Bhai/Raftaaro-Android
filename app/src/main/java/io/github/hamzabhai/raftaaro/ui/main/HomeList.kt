package io.github.hamzabhai.raftaaro.ui.main

import io.github.hamzabhai.raftaaro.core.Job
import io.github.hamzabhai.raftaaro.core.JobStatus

/** One list for the home screen: everything still in hand first, finished files below, each keeping its own order. */
fun homeOrder(jobs: List<Job>): List<Job> {
    val (done, rest) = jobs.partition { it.status == JobStatus.DONE }
    return rest + done
}

/** Received share of the file, 0..100, or null when the size is unknown. */
fun percentOf(job: Job): Int? {
    val total = job.totalBytes ?: return null
    if (total <= 0) return null
    return ((job.receivedBytes * 100) / total).toInt().coerceIn(0, 100)
}

/** The short label on a card's status pill. */
fun statusLabel(job: Job): String {
    val pct = percentOf(job)?.let { " $it%" } ?: ""
    return when (job.status) {
        JobStatus.QUEUED -> if (job.retryAt != null) "Retrying soon" else "Waiting"
        JobStatus.PROBING -> "Starting"
        JobStatus.DOWNLOADING -> "Downloading$pct"
        JobStatus.MERGING -> "Finishing"
        JobStatus.PAUSED -> "Paused$pct"
        JobStatus.ERROR -> "Failed"
        JobStatus.DONE -> "Done"
    }
}

/** "2 downloading · 1 paused · 3 done": a one-glance count above the list, empty parts left out. */
fun summaryLine(jobs: List<Job>): String {
    fun count(vararg s: JobStatus) = jobs.count { it.status in s }
    return listOf(
        count(JobStatus.QUEUED, JobStatus.PROBING, JobStatus.DOWNLOADING, JobStatus.MERGING) to "downloading",
        count(JobStatus.PAUSED) to "paused",
        count(JobStatus.ERROR) to "failed",
        count(JobStatus.DONE) to "done"
    ).filter { it.first > 0 }.joinToString(" · ") { "${it.first} ${it.second}" }
}
