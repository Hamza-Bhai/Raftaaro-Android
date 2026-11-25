package io.github.hamzabhai.raftaaro.core

/** Sites the user can sign in to inside the app. */
enum class Site(val label: String, val loginUrl: String, val cookieDomains: List<String>) {
    YOUTUBE("YouTube", "https://accounts.google.com/ServiceLogin?continue=https://m.youtube.com", listOf("youtube.com", "google.com")),
    INSTAGRAM("Instagram", "https://www.instagram.com/accounts/login/", listOf("instagram.com")),
    FACEBOOK("Facebook", "https://m.facebook.com/login/", listOf("facebook.com"))
}
