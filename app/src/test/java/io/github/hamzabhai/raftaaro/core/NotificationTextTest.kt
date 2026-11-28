package io.github.hamzabhai.raftaaro.core

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationTextTest {
    @Test fun `shows percent speed and time left while downloading`() =
        assertEquals("1 downloading · 45% · 3.2 MB/s · 2 min left", progressText(1, 0, 45, (3.2 * 1024 * 1024).toLong(), 125))

    @Test fun `leaves out what is not known`() =
        assertEquals("2 downloading · 1.5 KB/s", progressText(2, 0, null, 1536, null))

    @Test fun `no speed shown while it is zero`() = assertEquals("1 downloading · 10%", progressText(1, 0, 10, 0, null))

    @Test fun `says finishing when everything running is being finished`() =
        assertEquals("Finishing…", progressText(1, 1, 100, 0, null))

    @Test fun `counts finishing jobs next to downloading ones`() =
        assertEquals("2 downloading · 1 finishing · 60% · 1.0 MB/s", progressText(3, 1, 60, 1024 * 1024, null))

    @Test fun `starting when nothing runs yet`() = assertEquals("Starting…", progressText(0, 0, null, 0, null))
}
