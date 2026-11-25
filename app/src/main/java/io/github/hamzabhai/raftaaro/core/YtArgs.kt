package io.github.hamzabhai.raftaaro.core

import java.net.URI

/** Pauses between YouTube requests and downloads so the network does not look like a bot. */
private val YOUTUBE_PACING = listOf("--sleep-requests", "1", "--sleep-interval", "3", "--max-sleep-interval", "8")

private fun commonArgs(url: String, cookieFile: String?): List<String> = buildList {
    addAll(listOf("--encoding", "utf-8", "--no-warnings"))
    if (cookieFile != null) addAll(listOf("--cookies", cookieFile))
    if (isYouTubeUrl(url)) addAll(YOUTUBE_PACING)
}

/** A watch URL with &list= still means "this one video"; playlists are shared as playlist URLs. */
private fun singleVideo(url: String): Boolean = try {
    URI(url).rawQuery?.split('&')?.any { it.startsWith("v=") } == true || url.contains("youtu.be/")
} catch (_: Exception) {
    false
}

/** Arguments for `yt-dlp -J` (the URL itself is added by the caller). */
fun probeArgs(url: String, cookieFile: String?): List<String> = buildList {
    addAll(listOf("-J", "--flat-playlist"))
    addAll(commonArgs(url, cookieFile))
    if (singleVideo(url)) add("--no-playlist")
}

/** Arguments for a download into `workDir` (the URL itself is added by the caller). */
fun downloadArgs(url: String, choice: VideoChoice?, workDir: String, cookieFile: String?): List<String> = buildList {
    addAll(if (choice == null) listOf("-f", "b") else buildFormatSelector(choice))
    addAll(commonArgs(url, cookieFile))
    val tag = if (choice?.media == MediaType.VIDEO && choice.height != null) " ${choice.height}p" else ""
    addAll(listOf("-o", "$workDir/%(title).150B [%(id)s]$tag.%(ext)s"))
    addAll(listOf("--newline", "--progress-template", "download:$PROGRESS_TEMPLATE"))
    addAll(listOf("--print", "before_dl:$SIZE_MARK%(filesize,filesize_approx)s %(tbr)s %(duration)s"))
    addAll(listOf("--print", "after_move:$FILE_MARK%(filepath)s"))
    addAll(listOf("--no-quiet", "--progress", "--no-mtime", "--continue", "--no-playlist"))
}
