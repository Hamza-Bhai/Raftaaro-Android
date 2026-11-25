package io.github.hamzabhai.raftaaro.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RangesTest {
    @Test fun `splits into contiguous inclusive ranges, last takes remainder`() =
        assertEquals(listOf(ByteRange(0, 2), ByteRange(3, 5), ByteRange(6, 9)), splitRanges(10, 3))

    @Test fun `never makes more parts than bytes`() = assertEquals(2, splitRanges(2, 8).size)

    @Test fun `clamps parts to 1 to 16`() {
        assertEquals(listOf(ByteRange(0, 99)), splitRanges(100, 0))
        assertEquals(16, splitRanges(1000, 50).size)
    }
}
