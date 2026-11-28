package io.github.hamzabhai.raftaaro.core

import java.util.Locale
import kotlin.math.roundToLong

private val UNITS = listOf("B", "KB", "MB", "GB", "TB")

fun formatBytes(bytes: Long?): String {
    if (bytes == null) return "?"
    var v = bytes.toDouble()
    var i = 0
    while (v >= 1024 && i < UNITS.size - 1) {
        v /= 1024
        i++
    }
    if (i == 0) return "$bytes B"
    return String.format(Locale.US, if (i >= 3) "%.2f %s" else "%.1f %s", v, UNITS[i])
}

fun formatSpeed(bps: Long): String = "${formatBytes(bps)}/s"

fun formatEta(sec: Long?): String = when {
    sec == null -> ""
    sec < 60 -> "$sec sec left"
    sec < 3600 -> "${(sec / 60.0).roundToLong()} min left"
    else -> "${(sec / 3600.0).roundToLong()} hr left"
}

fun formatDuration(sec: Int?): String {
    if (sec == null) return ""
    val h = sec / 3600
    val m = (sec % 3600) / 60
    val s = sec % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

/**
 * The ongoing notification's line: "1 downloading · 45% · 3.2 MB/s · 2 min left".
 * `running` counts jobs downloading or finishing; `finishing` those being joined and saved.
 */
fun progressText(running: Int, finishing: Int, percent: Int?, speedBps: Long, etaSec: Long?): String {
    if (running == 0) return "Starting…"
    val downloading = running - finishing
    if (downloading == 0) return "Finishing…"
    return listOfNotNull(
        "$downloading downloading",
        finishing.takeIf { it > 0 }?.let { "$it finishing" },
        percent?.let { "$it%" },
        speedBps.takeIf { it > 0 }?.let(::formatSpeed),
        formatEta(etaSec).ifEmpty { null }
    ).joinToString(" · ")
}
