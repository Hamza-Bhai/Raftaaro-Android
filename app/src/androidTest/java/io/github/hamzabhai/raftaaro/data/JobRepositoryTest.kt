package io.github.hamzabhai.raftaaro.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hamzabhai.raftaaro.core.AudioFormat
import io.github.hamzabhai.raftaaro.core.Job
import io.github.hamzabhai.raftaaro.core.JobKind
import io.github.hamzabhai.raftaaro.core.JobStatus
import io.github.hamzabhai.raftaaro.core.MediaType
import io.github.hamzabhai.raftaaro.core.VideoChoice
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class JobRepositoryTest {
    private val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java).build()
    private val repo = JobRepository(db.jobDao())

    @After fun close() = db.close()

    private val full = Job(
        id = "a", kind = JobKind.VIDEO, url = "https://youtu.be/x", title = "T", thumbnail = "th",
        choice = VideoChoice(MediaType.AUDIO, null, AudioFormat.M4A), folder = "List", status = JobStatus.ERROR,
        totalBytes = 100, receivedBytes = 50, speedBps = 7, etaSec = 3, error = "e", attempts = 1, retryAt = 9,
        createdAt = 1, savedUri = "content://x", fileName = "f.m4a"
    )

    @Test fun roundTripsJobs() = runTest {
        repo.add(full)
        assertEquals(full, repo.get("a"))
        repo.add(Job(id = "b", kind = JobKind.FILE, url = "https://x.com/a.zip", title = "a.zip", createdAt = 2))
        assertNull(repo.get("b")!!.choice)
        repo.update(full.copy(status = JobStatus.DONE))
        assertEquals(JobStatus.DONE, repo.get("a")!!.status)
        repo.delete("a")
        assertNull(repo.get("a"))
    }

    @Test fun jobsFlowEmitsNewestFirst() = runTest {
        repo.add(full.copy(id = "old", createdAt = 1))
        repo.add(full.copy(id = "new", createdAt = 2))
        assertEquals(listOf("new", "old"), repo.jobs.first().map { it.id })
    }

    @Test fun updateAllWritesEveryJob() = runTest {
        repo.add(full.copy(id = "1"))
        repo.add(full.copy(id = "2"))
        repo.updateAll(repo.all().map { it.copy(status = JobStatus.PAUSED) })
        assertEquals(setOf(JobStatus.PAUSED), repo.all().map { it.status }.toSet())
    }
}
