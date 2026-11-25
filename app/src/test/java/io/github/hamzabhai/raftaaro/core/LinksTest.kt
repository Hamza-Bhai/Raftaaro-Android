package io.github.hamzabhai.raftaaro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinksTest {
    @Test fun `finds a link inside surrounding text`() =
        assertEquals("https://youtu.be/x", extractFirstUrl("dekho https://youtu.be/x yeh"))

    @Test fun `strips trailing punctuation`() =
        assertEquals("https://x.com/a.zip", extractFirstUrl("link: https://x.com/a.zip)."))

    @Test fun `returns null without a link`() {
        assertNull(extractFirstUrl("koi link nahi"))
        assertNull(extractFirstUrl(""))
    }

    @Test fun `finds the link in an Instagram share text`() =
        assertEquals(
            "https://www.instagram.com/reel/abc/",
            extractFirstUrl("Look at this https://www.instagram.com/reel/abc/ via @x")
        )

    @Test fun `returns null for share text without a link`() = assertNull(extractFirstUrl("hello there"))

    @Test fun `accepts a trimmed http(s) url`() {
        assertTrue(isDownloadableUrl(" https://x.com/a.zip "))
        assertTrue(isDownloadableUrl("http://example.com"))
    }

    @Test fun `rejects text with more than one token`() = assertFalse(isDownloadableUrl("hello https://x.com"))

    @Test fun `rejects other schemes and junk`() {
        assertFalse(isDownloadableUrl("ftp://x"))
        assertFalse(isDownloadableUrl("javascript:alert(1)"))
        assertFalse(isDownloadableUrl(""))
    }

    @Test fun `matches YouTube hosts only`() {
        assertTrue(isYouTubeUrl("https://youtu.be/x"))
        assertTrue(isYouTubeUrl("https://music.youtube.com/watch?v=x"))
        assertFalse(isYouTubeUrl("https://www.facebook.com/x"))
        assertFalse(isYouTubeUrl("https://notyoutube.com/x"))
    }

    @Test fun `detects known video hosts and subdomains`() {
        assertTrue(isVideoSiteUrl("https://youtu.be/abc"))
        assertTrue(isVideoSiteUrl("https://m.youtube.com/watch?v=1"))
        assertTrue(isVideoSiteUrl("https://www.instagram.com/reel/x"))
        assertTrue(isVideoSiteUrl("https://fb.watch/x"))
    }

    @Test fun `rejects other hosts and look-alikes`() {
        assertFalse(isVideoSiteUrl("https://example.com/a.mp4"))
        assertFalse(isVideoSiteUrl("https://notyoutube.com/x"))
        assertFalse(isVideoSiteUrl("not a url"))
    }

    @Test fun `maps sites that use a sign-in`() {
        assertEquals(Site.YOUTUBE, usesSiteLogin("https://youtu.be/x"))
        assertEquals(Site.INSTAGRAM, usesSiteLogin("https://www.instagram.com/reel/x"))
        assertEquals(Site.FACEBOOK, usesSiteLogin("https://fb.watch/x"))
        assertNull(usesSiteLogin("https://vimeo.com/1"))
    }
}
