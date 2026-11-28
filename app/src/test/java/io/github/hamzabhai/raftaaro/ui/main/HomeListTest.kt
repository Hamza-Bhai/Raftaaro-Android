package io.github.hamzabhai.raftaaro.ui.main

import io.github.hamzabhai.raftaaro.core.Job
import io.github.hamzabhai.raftaaro.core.JobKind
import io.github.hamzabhai.raftaaro.core.JobStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeListTest {
    private fun job(id: String, status: JobStatus, total: Long? = 200, got: Long = 50) =
        Job(id = id, kind = JobKind.FILE, url = "https://x.com/$id", title = id, status = status, totalBytes = total, receivedBytes = got)

    @Test fun finishedJobsGoBelowEverythingElseKeepingTheirOrder() {
        val jobs = listOf(job("d1", JobStatus.DONE), job("a", JobStatus.DOWNLOADING), job("d2", JobStatus.DONE), job("e", JobStatus.ERROR), job("p", JobStatus.PAUSED))
        assertEquals(listOf("a", "e", "p", "d1", "d2"), homeOrder(jobs).map { it.id })
    }

    @Test fun percentNeedsAKnownSize() {
        assertEquals(25, percentOf(job("a", JobStatus.DOWNLOADING)))
        assertNull(percentOf(job("a", JobStatus.DOWNLOADING, total = null)))
        assertNull(percentOf(job("a", JobStatus.DOWNLOADING, total = 0)))
        assertEquals(100, percentOf(job("a", JobStatus.DOWNLOADING, total = 10, got = 20)))
    }

    @Test fun labelsShowProgressWhereItIsKnown() {
        assertEquals("Downloading 25%", statusLabel(job("a", JobStatus.DOWNLOADING)))
        assertEquals("Downloading", statusLabel(job("a", JobStatus.DOWNLOADING, total = null)))
        assertEquals("Paused 25%", statusLabel(job("a", JobStatus.PAUSED)))
        assertEquals("Failed", statusLabel(job("a", JobStatus.ERROR)))
        assertEquals("Done", statusLabel(job("a", JobStatus.DONE)))
    }

    @Test fun summaryCountsEachKindAndSkipsEmptyOnes() {
        val jobs = listOf(job("a", JobStatus.DOWNLOADING), job("q", JobStatus.QUEUED), job("d", JobStatus.DONE), job("e", JobStatus.ERROR))
        assertEquals("2 downloading · 1 failed · 1 done", summaryLine(jobs))
        assertEquals("", summaryLine(emptyList()))
    }
}
