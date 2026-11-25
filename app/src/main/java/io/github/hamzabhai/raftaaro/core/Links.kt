package io.github.hamzabhai.raftaaro.core

import java.net.URI

private val VIDEO_HOSTS = listOf(
    "youtube.com", "youtu.be", "facebook.com", "fb.watch", "instagram.com",
    "tiktok.com", "x.com", "twitter.com", "vimeo.com", "dailymotion.com"
)

private fun httpHost(text: String): String? = try {
    val uri = URI(text)
    if (uri.scheme?.lowercase() in setOf("http", "https")) uri.host?.lowercase() else null
} catch (_: Exception) {
    null
}

/** True when the text is exactly one http(s) URL (surrounding whitespace allowed). */
fun isDownloadableUrl(text: String): Boolean {
    val t = text.trim()
    return t.isNotEmpty() && !t.any { it.isWhitespace() } && httpHost(t) != null
}

/** First http(s) link found in free text (e.g. a share or the clipboard), without trailing punctuation. */
fun extractFirstUrl(text: String): String? {
    val m = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE).find(text) ?: return null
    val url = m.value.replace(Regex("""[).,;:!?\]]+$"""), "")
    return if (httpHost(url) != null) url else null
}

private fun hostIs(url: String, hosts: List<String>): Boolean {
    val host = httpHost(url.trim()) ?: return false
    return hosts.any { host == it || host.endsWith(".$it") }
}

/** YouTube links get extra care: slower requests and one download at a time. */
fun isYouTubeUrl(url: String): Boolean = hostIs(url, listOf("youtube.com", "youtu.be"))

/** The site whose saved sign-in applies to this URL, if any. */
fun usesSiteLogin(url: String): Site? = when {
    isYouTubeUrl(url) -> Site.YOUTUBE
    hostIs(url, listOf("instagram.com")) -> Site.INSTAGRAM
    hostIs(url, listOf("facebook.com", "fb.watch")) -> Site.FACEBOOK
    else -> null
}

/** True when the URL belongs to a well-known video site handled by yt-dlp. */
fun isVideoSiteUrl(url: String): Boolean = hostIs(url, VIDEO_HOSTS)
