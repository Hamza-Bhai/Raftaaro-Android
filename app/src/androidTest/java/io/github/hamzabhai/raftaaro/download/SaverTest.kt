package io.github.hamzabhai.raftaaro.download

import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hamzabhai.raftaaro.core.Settings
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SaverTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val saver = Saver(ctx)
    private val saved = mutableListOf<String>()
    private val tag = UUID.randomUUID().toString().take(8)

    @After fun cleanup(): Unit = runBlocking { saved.forEach { saver.delete(it) } }

    private fun temp(name: String, bytes: ByteArray = ByteArray(1234) { it.toByte() }) =
        File(ctx.cacheDir, "saver-$tag/$name").apply { parentFile!!.mkdirs(); writeBytes(bytes) }

    private fun relativePath(uri: String): String =
        ctx.contentResolver.query(Uri.parse(uri), arrayOf(MediaStore.MediaColumns.RELATIVE_PATH), null, null, null)!!
            .use { it.moveToFirst(); it.getString(0) }

    @Test fun savesIntoDownloadRaftaaroVideosAndReturnsAReadableUri(): Unit = runBlocking {
        val src = temp("clip-$tag.mp4")
        val bytes = src.readBytes()
        val s = saver.save(src, Bucket.VIDEOS, null, Settings())
        saved += s.uri
        assertEquals("Download/Raftaaro/Videos/", relativePath(s.uri))
        assertEquals("clip-$tag.mp4", s.name)
        assertArrayEquals(bytes, ctx.contentResolver.openInputStream(Uri.parse(s.uri))!!.use { it.readBytes() })
        assertFalse(src.exists())
    }

    @Test fun savingTheSameNameTwiceKeepsBothFiles(): Unit = runBlocking {
        val a = saver.save(temp("same-$tag.mp3"), Bucket.AUDIO, null, Settings())
        val b = saver.save(temp("same-$tag.mp3"), Bucket.AUDIO, null, Settings())
        saved += a.uri
        saved += b.uri
        assertNotEquals(a.uri, b.uri)
        assertNotEquals(a.name, b.name)
        assertTrue(ctx.contentResolver.openInputStream(Uri.parse(a.uri))!!.use { it.read() } >= 0)
        assertTrue(ctx.contentResolver.openInputStream(Uri.parse(b.uri))!!.use { it.read() } >= 0)
    }

    @Test fun savesAPlaylistVideoIntoItsSubfolder(): Unit = runBlocking {
        val s = saver.save(temp("p-$tag.mp4"), Bucket.VIDEOS, "My List $tag", Settings())
        saved += s.uri
        assertEquals("Download/Raftaaro/Videos/My List $tag/", relativePath(s.uri))
    }

    @Test fun deleteRemovesTheFile(): Unit = runBlocking {
        val s = saver.save(temp("gone-$tag.zip"), Bucket.FILES, null, Settings())
        assertTrue(saver.delete(s.uri))
        assertTrue(runCatching { ctx.contentResolver.openInputStream(Uri.parse(s.uri))!!.close() }.isFailure)
    }
}
