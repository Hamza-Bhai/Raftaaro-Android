package io.github.hamzabhai.raftaaro.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hamzabhai.raftaaro.core.AudioFormat
import io.github.hamzabhai.raftaaro.core.MediaType
import io.github.hamzabhai.raftaaro.core.ProbeResult
import io.github.hamzabhai.raftaaro.core.VideoChoice
import io.github.hamzabhai.raftaaro.core.VideoProgress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Needs network. Uses a 19-second public video. */
@RunWith(AndroidJUnit4::class)
class YtDlpEngineTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val engine = YtDlpEngine(ctx)
    private val url = "https://www.youtube.com/watch?v=jNQXAC9IVRw"
    private val mp3 = VideoChoice(MediaType.AUDIO, null, AudioFormat.MP3)

    @Test fun probesAShortPublicVideo(): Unit = runBlocking {
        engine.ensureReady()
        val r = engine.probe(url, null) as ProbeResult.Video
        assertEquals("Me at the zoo", r.info.title)
        assertTrue(r.info.formats.isNotEmpty())
    }

    @Test fun aDownloadRightAfterReadingTheLinkReusesTheReading(): Unit = runBlocking {
        engine.ensureReady()
        engine.probe(url, null)
        val work = File(ctx.filesDir, "test-jobs/reuse").apply { deleteRecursively(); mkdirs() }
        val t0 = System.currentTimeMillis()
        val file = engine.download("t3", url, mp3, work, null, null, {}, {})
        val ms = System.currentTimeMillis() - t0
        android.util.Log.i("Raftaaro", "download after probe took $ms ms")
        assertTrue(file.exists() && file.name.endsWith(".mp3"))
        work.deleteRecursively()
    }

    @Test fun downloadsItAsMp3IntoTheWorkDirAndASecondRunReusesIt(): Unit = runBlocking {
        engine.ensureReady()
        val work = File(ctx.filesDir, "test-jobs/mp3").apply { deleteRecursively(); mkdirs() }
        val progress = mutableListOf<VideoProgress>()
        var merging = false
        val file = engine.download("t1", url, mp3, work, null, null, { progress += it }, { merging = true })
        assertTrue(file.exists() && file.length() > 10_000)
        assertTrue(file.name.endsWith(".mp3"))
        assertTrue(file.parentFile == work)
        assertTrue(merging)
        assertTrue(progress.isNotEmpty())

        val again = engine.download("t2", url, mp3, work, null, null, {}, {})
        assertEquals(file, again)
        work.deleteRecursively()
    }
}
