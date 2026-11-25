package io.github.hamzabhai.raftaaro.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hamzabhai.raftaaro.core.Settings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SettingsStoreTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun settingsHasDefaultsOnAFreshInstall() = runTest {
        val s = SettingsStore(ctx, "test-${UUID.randomUUID()}").current()
        assertEquals(Settings(), s)
        assertEquals(3, s.maxConcurrent)
        assertEquals(8, s.partsPerFile)
        assertEquals(true, s.clipboardPrompt)
        assertEquals(true, s.useSignIns)
    }

    @Test fun savesPartialSettings() = runTest {
        val name = "test-${UUID.randomUUID()}"
        SettingsStore(ctx, name).update { it.copy(maxConcurrent = 5, videosDir = "content://tree", lastYtDlpUpdate = 42) }
        val s = SettingsStore(ctx, name).current()
        assertEquals(5, s.maxConcurrent)
        assertEquals("content://tree", s.videosDir)
        assertEquals(42L, s.lastYtDlpUpdate)
        assertEquals(8, s.partsPerFile)
    }
}
