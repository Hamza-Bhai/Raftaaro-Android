package io.github.hamzabhai.raftaaro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueRulesTest {
    private fun job(n: Int, url: String = "https://x.com/$n.zip", status: JobStatus = JobStatus.QUEUED, retryAt: Long? = null) =
        Job(id = "$n", kind = JobKind.FILE, url = url, title = "$n", status = status, createdAt = n.toLong(), retryAt = retryAt)

    private fun yt(n: Int, status: JobStatus = JobStatus.QUEUED, retryAt: Long? = null) =
        job(n, "https://www.youtube.com/watch?v=$n", status, retryAt).copy(kind = JobKind.VIDEO)

    @Test fun `runs at most maxConcurrent jobs`() {
        val jobs = (1..5).map { job(it) }
        assertEquals(listOf("1", "2"), jobsToStart(jobs, emptySet(), 2, 0))
        assertEquals(listOf("2"), jobsToStart(jobs, setOf("1"), 2, 0).take(1))
        assertEquals(emptyList<String>(), jobsToStart(jobs, setOf("1", "3"), 2, 0))
    }

    @Test fun `starts the oldest first`() =
        assertEquals(listOf("1"), jobsToStart(listOf(job(3), job(1), job(2)), emptySet(), 1, 0))

    @Test fun `marks interrupted downloads paused on startup`() {
        val out = onAppStart(
            listOf(
                job(1, status = JobStatus.DOWNLOADING).copy(speedBps = 10, etaSec = 5),
                job(2, status = JobStatus.MERGING),
                job(3, status = JobStatus.PROBING),
                job(4, status = JobStatus.DONE),
                job(5)
            )
        )
        assertEquals(
            listOf(JobStatus.PAUSED, JobStatus.PAUSED, JobStatus.PAUSED, JobStatus.DONE, JobStatus.QUEUED),
            out.map { it.status }
        )
        assertEquals(0L, out[0].speedBps)
        assertNull(out[0].etaSec)
    }

    @Test fun `runs one YouTube download at a time while other downloads continue`() {
        val jobs = listOf(yt(1), yt(2), job(3))
        assertEquals(listOf("1", "3"), jobsToStart(jobs, emptySet(), 3, 0))
        assertEquals(listOf("3"), jobsToStart(jobs, setOf("1"), 3, 0))
    }

    @Test fun `backs off for 10 minutes after a rate limit and then retries by itself`() {
        val failed = afterFailure(yt(1, JobStatus.DOWNLOADING), RATE_LIMIT_MESSAGE, 1000)
        assertEquals(JobStatus.QUEUED, failed.status)
        assertEquals(1000 + 10 * 60 * 1000L, failed.retryAt)
        assertEquals(1, failed.attempts)
        val jobs = listOf(failed, yt(2))
        assertEquals(emptyList<String>(), jobsToStart(jobs, emptySet(), 3, 1000 + 60_000))
        assertEquals(failed.retryAt, nextWake(jobs, 1000 + 60_000))
        assertEquals(listOf("1"), jobsToStart(jobs, emptySet(), 3, failed.retryAt!! + 1))
    }

    @Test fun `gives up after two automatic retries`() {
        var j = yt(1, JobStatus.DOWNLOADING)
        repeat(2) { j = afterFailure(j, RATE_LIMIT_MESSAGE, 0).copy(status = JobStatus.DOWNLOADING) }
        val last = afterFailure(j, RATE_LIMIT_MESSAGE, 0)
        assertEquals(JobStatus.ERROR, last.status)
        assertEquals(RATE_LIMIT_MESSAGE, last.error)
    }

    @Test fun `other failures become errors with their message`() {
        val out = afterFailure(job(1, status = JobStatus.DOWNLOADING), "HTTP 404", 0)
        assertEquals(JobStatus.ERROR, out.status)
        assertEquals("HTTP 404", out.error)
        assertEquals(0L, out.speedBps)
    }

    @Test fun `nothing to wake for without a cooldown`() = assertNull(nextWake(listOf(job(1)), 0))

    private fun entry(n: Int, status: JobStatus = JobStatus.QUEUED, folder: String? = "Mix") = yt(n, status).copy(folder = folder)

    @Test fun `a playlist video reads ahead the oldest queued YouTube playlist video`() =
        assertEquals("https://www.youtube.com/watch?v=2", readAheadFor(entry(1, JobStatus.DOWNLOADING), listOf(entry(3), entry(1, JobStatus.DOWNLOADING), entry(2))))

    @Test fun `nothing is read ahead for a single video or when no playlist video waits`() {
        assertNull(readAheadFor(entry(1, folder = null), listOf(entry(1, folder = null), entry(2))))
        assertNull(readAheadFor(entry(1), listOf(entry(1), entry(2, JobStatus.DONE), entry(3, JobStatus.PAUSED), yt(4), job(5).copy(folder = "Mix"))))
    }

    @Test fun `the first read starts at once and later ones keep the gap after the last read`() {
        assertEquals(100L, nextReadAt(null, 100, 5_000))
        assertEquals(6_000L, nextReadAt(1_000, 2_000, 5_000))
        assertEquals(9_000L, nextReadAt(1_000, 9_000, 5_000))
    }
}
