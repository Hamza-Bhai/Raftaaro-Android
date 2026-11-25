package io.github.hamzabhai.raftaaro.core

import org.junit.Assert.assertEquals
import org.junit.Test

class ByteFormatTest {
    @Test fun `formats with binary units`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("4.0 MB", formatBytes(4L * 1024 * 1024))
        assertEquals("1.20 GB", formatBytes((1.2 * 1024 * 1024 * 1024).toLong()))
    }

    @Test fun `handles unknown sizes`() = assertEquals("?", formatBytes(null))

    @Test fun `formats speed per second`() = assertEquals("1.5 KB/s", formatSpeed(1536))

    @Test fun `uses short English wording`() {
        assertEquals("30 sec left", formatEta(30))
        assertEquals("2 min left", formatEta(125))
        assertEquals("2 hr left", formatEta(7300))
        assertEquals("", formatEta(null))
    }

    @Test fun `formats durations`() {
        assertEquals("0:19", formatDuration(19))
        assertEquals("1:02:05", formatDuration(3725))
        assertEquals("", formatDuration(null))
    }
}
