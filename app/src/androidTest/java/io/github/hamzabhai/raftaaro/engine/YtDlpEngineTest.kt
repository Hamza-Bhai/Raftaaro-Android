package io.github.hamzabhai.raftaaro.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hamzabhai.raftaaro.core.AudioFormat
import io.github.hamzabhai.raftaaro.core.MediaType
import io.github.hamzabhai.raftaaro.core.ProbeResult
import io.github.hamzabhai.raftaaro.core.VideoChoice
import io.github.hamzabhai.raftaaro.core.VideoProgress
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
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

    @Test fun aPlaylistVideoIsReadAheadAndBothDownloadDirectly(): Unit = runBlocking {
        val fast = YtDlpEngine(ctx, http = OkHttpClient())
        fast.ensureReady()
        // nothing read yet, as for a freshly queued playlist
        File(ctx.cacheDir, "yt-dlp/info").deleteRecursively()
        val second = "$url&feature=share"
        val w1 = File(ctx.filesDir, "test-jobs/pl1").apply { deleteRecursively(); mkdirs() }
        val w2 = File(ctx.filesDir, "test-jobs/pl2").apply { deleteRecursively(); mkdirs() }
        val t0 = System.currentTimeMillis()
        val f1 = fast.download("p1", url, mp3, w1, null, null, {}, {}, paced = true, readAhead = second)
        val t1 = System.currentTimeMillis()
        val f2 = fast.download("p2", second, mp3, w2, null, null, {}, {}, paced = true)
        val t2 = System.currentTimeMillis()
        android.util.Log.i("Raftaaro", "playlist: first ${t1 - t0} ms, second ${t2 - t1} ms")
        assertTrue(f1.isFile && f1.name.endsWith(".mp3"))
        assertTrue(f2.isFile && f2.name.endsWith(".mp3"))
        // both pages were read and kept (the second one ahead), which is what a direct download needs
        assertTrue(File(ctx.cacheDir, "yt-dlp/info").listFiles().orEmpty().size == 2)
        w1.deleteRecursively()
        w2.deleteRecursively()
    }
}
