package io.github.hamzabhai.raftaaro.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Needs network. */
@RunWith(AndroidJUnit4::class)
class PlayerWarmUpWorkerTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val players = File(ctx.cacheDir, "yt-dlp/challenge-solver")

    private fun playerFiles() = players.listFiles().orEmpty().filter { it.name.startsWith("player") }

    @Test fun aWarmUpWithAnEmptyCacheLeavesAPreparedPlayer(): Unit = runBlocking {
        // as after YouTube changes players: nothing prepared yet
        playerFiles().forEach { it.delete() }
        val worker = TestListenableWorkerBuilder<PlayerWarmUpWorker>(ctx).build()
        val result = worker.doWork()
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue("no prepared player in $players", playerFiles().isNotEmpty())
    }

    @Test fun startingTheAppSchedulesTheDailyWarmUp() {
        val infos = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(PlayerWarmUpWorker.NAME).get()
        assertEquals(1, infos.size)
        assertTrue(infos[0].state == WorkInfo.State.ENQUEUED || infos[0].state == WorkInfo.State.RUNNING)
    }
}
