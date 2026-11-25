package io.github.hamzabhai.raftaaro.core

private const val DAY_MS = 24 * 60 * 60 * 1000L

/** yt-dlp must stay current for YouTube to keep working; check for a new version at most once a day. */
fun shouldUpdateYtDlp(lastUpdate: Long?, now: Long): Boolean =
    lastUpdate == null || lastUpdate > now || now - lastUpdate >= DAY_MS
