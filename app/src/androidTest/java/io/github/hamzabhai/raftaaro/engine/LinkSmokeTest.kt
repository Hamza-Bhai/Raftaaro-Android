package io.github.hamzabhai.raftaaro.engine

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hamzabhai.raftaaro.core.MediaType
import io.github.hamzabhai.raftaaro.core.ProbeResult
import io.github.hamzabhai.raftaaro.core.VideoChoice
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Manual check of real links: run with `-Pandroid.testInstrumentationRunnerArguments.links=url1,url2`.
 * Each link is read and downloaded (at most 90 s) at 360p, or at Best where the site gives no heights
 * (LinkedIn), as the share sheet then offers only Best; what happened is logged under "RaftaaroSmoke"
 * and the engine logs whether it went direct. Skipped in normal runs. A tab (channel Shorts) is
 * resolved to its first video, kept in its /shorts/ form.
 */
@RunWith(AndroidJUnit4::class)
class LinkSmokeTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun eachLinkDownloadsOrFailsClearly(): Unit = runBlocking {
        val links = InstrumentationRegistry.getArguments().getString("links")?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
        assumeTrue("no links given", !links.isNullOrEmpty())
        val engine = YtDlpEngine(ctx, http = OkHttpClient())
        engine.ensureReady()
        for ((i, given) in links!!.withIndex()) {
            var link = given
            var t0 = System.currentTimeMillis()
            var probe = runCatching { engine.probe(link, null) }
            val tab = probe.getOrNull() as? ProbeResult.Playlist
            if (tab != null && tab.info.entries.isNotEmpty()) {
                link = tab.info.entries.first().url.let { if ("/shorts" in given) it.replace("watch?v=", "shorts/") else it }
                t0 = System.currentTimeMillis()
                probe = runCatching { engine.probe(link, null) }
            }
            val read = probe.fold(
                { r -> if (r is ProbeResult.Video) "video '${r.info.title}' ${r.info.formats.size} formats, ${r.info.durationSec}s" else r.javaClass.simpleName },
                { "FAILED: ${it.message}" }
            )
            Log.i("RaftaaroSmoke", "[$i] $link read in ${System.currentTimeMillis() - t0} ms: $read")
            if (probe.getOrNull() !is ProbeResult.Video) continue
            val heights = (probe.getOrNull() as ProbeResult.Video).info.formats.mapNotNull { it.height }
            val choice = VideoChoice(MediaType.VIDEO, if (heights.isEmpty()) null else 360)
            val work = File(ctx.filesDir, "test-jobs/smoke$i").apply { deleteRecursively(); mkdirs() }
            var last = 0L
            t0 = System.currentTimeMillis()
            val result = runCatching {
                withTimeoutOrNull(90_000) {
                    engine.download("smoke$i", link, choice, work, null, null, { last = it.received }, {})
                }
            }
            val ms = System.currentTimeMillis() - t0
            val outcome = result.fold(
                { f -> if (f == null) "STILL RUNNING after 90 s ($last bytes), stopped" else "done: ${f.name} ${f.length()} bytes" },
                { "FAILED: ${it.message}" }
            )
            Log.i("RaftaaroSmoke", "[$i] download (${choice.height?.let { "${it}p" } ?: "Best"}) in $ms ms: $outcome")
            work.deleteRecursively()
        }
    }
}
