package io.github.hamzabhai.raftaaro.core

import kotlinx.serialization.Serializable

/** Byte range; `end` is inclusive. */
@Serializable
data class ByteRange(val start: Long, val end: Long)

/** Split `size` bytes into at most `parts` (clamped to 1..16) contiguous inclusive ranges. */
fun splitRanges(size: Long, parts: Int): List<ByteRange> {
    val count = maxOf(1L, minOf(16L, parts.toLong(), size)).toInt()
    val chunk = size / count
    return List(count) { i ->
        val start = i * chunk
        ByteRange(start, if (i == count - 1) size - 1 else start + chunk - 1)
    }
}
