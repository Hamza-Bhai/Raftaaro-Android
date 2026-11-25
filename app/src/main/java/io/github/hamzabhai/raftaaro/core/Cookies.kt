package io.github.hamzabhai.raftaaro.core

/** Sites the user can sign in to inside the app. */
enum class Site(val label: String, val loginUrl: String, val cookieDomains: List<String>) {
    YOUTUBE("YouTube", "https://accounts.google.com/ServiceLogin?continue=https://m.youtube.com", listOf("youtube.com", "google.com")),
    INSTAGRAM("Instagram", "https://www.instagram.com/accounts/login/", listOf("instagram.com")),
    FACEBOOK("Facebook", "https://m.facebook.com/login/", listOf("facebook.com"))
}

private const val HEADER = "# Netscape HTTP Cookie File"

/**
 * A Netscape cookie file for `yt-dlp --cookies`. Keys are domains, values are what WebView's
 * CookieManager.getCookie() returns ("a=1; b=2"). Each cookie is written for the whole domain.
 */
fun toNetscapeCookies(cookieHeaders: Map<String, String?>): String {
    val lines = mutableListOf(HEADER)
    for ((domain, header) in cookieHeaders) {
        for (pair in header.orEmpty().split(';')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = pair.substring(0, eq).trim()
            val value = pair.substring(eq + 1).trim()
            if (name.isEmpty() || listOf(name, value).any { s -> s.any { it == '\t' || it == '\r' || it == '\n' } }) continue
            lines += listOf(".$domain", "TRUE", "/", "TRUE", "0", name, value).joinToString("\t")
        }
    }
    return lines.joinToString("\n") + "\n"
}

/** Number of cookies in a Netscape cookie file; 0 means "signed out". */
fun cookieCount(file: String): Int = file.lines().count { it.isNotBlank() && !it.startsWith("#") }
