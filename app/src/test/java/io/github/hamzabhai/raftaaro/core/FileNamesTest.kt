package io.github.hamzabhai.raftaaro.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileNamesTest {
    @Test fun `replaces illegal characters`() =
        assertEquals("a_b__c_d_e_f_g_h_i", sanitizeFileName("a<b>:c\"d/e\\f|g?h*i"))

    @Test fun `strips trailing dots and spaces`() = assertEquals("song", sanitizeFileName("song. "))

    @Test fun `falls back to download for empty names`() {
        assertEquals("download", sanitizeFileName(""))
        assertEquals("download", sanitizeFileName(" . "))
    }

    @Test fun `limits length to 200 characters`() = assertTrue(sanitizeFileName("x".repeat(300)).length <= 200)

    @Test fun `cleans emoji slash and colon titles and keeps the extension under 200 chars`() {
        val out = sanitizeFileName("🎵 a/b: c " + "x".repeat(300) + ".mp4")
        assertTrue(out.length <= 200)
        assertTrue(out.endsWith(".mp4"))
        assertFalse(out.contains('/') || out.contains(':'))
    }

    @Test fun `never cuts an emoji in half`() {
        val out = sanitizeFileName("x".repeat(199) + "🎵🎵")
        assertFalse(Character.isHighSurrogate(out.last()))
    }

    @Test fun `returns the plain name when free`() = assertEquals("a.mp4", uniqueName("a.mp4") { false })

    @Test fun `uniqueName adds a counter`() =
        assertEquals("a (2).mp4", uniqueName("a.mp4") { it == "a.mp4" || it == "a (1).mp4" })
}
