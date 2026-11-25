package io.github.hamzabhai.raftaaro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CookiesTest {
    @Test fun `writes the Netscape cookie file format yt-dlp reads`() = assertEquals(
        "# Netscape HTTP Cookie File\n" +
            ".instagram.com\tTRUE\t/\tTRUE\t0\tsessionid\tabc\n" +
            ".instagram.com\tTRUE\t/\tTRUE\t0\tcsrftoken\tx=y\n",
        toNetscapeCookies(mapOf("instagram.com" to "sessionid=abc; csrftoken=x=y"))
    )

    @Test fun `drops cookies that would break the file`() {
        val out = toNetscapeCookies(mapOf("facebook.com" to "good=1; bad=a\tb"))
        assertEquals(2, out.lines().filter { it.isNotEmpty() }.size)
        assertFalse(out.contains("bad"))
    }

    @Test fun `ignores domains without cookies`() =
        assertEquals("# Netscape HTTP Cookie File\n", toNetscapeCookies(mapOf("youtube.com" to null, "google.com" to "")))

    @Test fun `counts cookie lines`() {
        assertEquals(2, cookieCount(toNetscapeCookies(mapOf("youtube.com" to "a=1; b=2"))))
        assertEquals(0, cookieCount("# Netscape HTTP Cookie File\n"))
    }
}

class UpdatePolicyTest {
    private val day = 24 * 60 * 60 * 1000L

    @Test fun `updates when it never has`() = assertEquals(true, shouldUpdateYtDlp(null, 1000))

    @Test fun `updates once a day at most`() {
        assertEquals(false, shouldUpdateYtDlp(1000, 1000 + day - 1))
        assertEquals(true, shouldUpdateYtDlp(1000, 1000 + day))
    }

    @Test fun `updates when the clock went backwards`() = assertEquals(true, shouldUpdateYtDlp(5000, 1000))
}
