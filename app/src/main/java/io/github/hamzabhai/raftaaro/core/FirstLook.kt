package io.github.hamzabhai.raftaaro.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder

/**
 * YouTube's oEmbed answers title, channel and thumbnail of a video in one small request (~0.5 s),
 * where even yt-dlp's quickest reading needs Python (~6 s on a phone). Used for the share sheet's
 * first look; length and qualities come with the full reading.
 */
fun oembedUrl(url: String): String = "https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(url, "UTF-8")

private val json = Json { ignoreUnknownKeys = true }

/** A first look at `url` from an oEmbed answer: no length, no formats. Null when it has no title. */
fun parseOembed(url: String, body: String): VideoInfo? {
    val o = runCatching { json.parseToJsonElement(body) as JsonObject }.getOrNull() ?: return null
    fun str(key: String) = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
    val title = str("title") ?: return null
    return VideoInfo(url = url, title = title, durationSec = null, uploader = str("author_name"), thumbnail = str("thumbnail_url"), formats = emptyList())
}
