package io.github.hamzabhai.raftaaro.core

private val ILLEGAL = Regex("""[<>:"/\\|?*\x00-\x1f]""")
private const val MAX_LEN = 200

/** Make a string safe as a file name on Android shared storage (FAT/exFAT rules). */
fun sanitizeFileName(name: String): String {
    var out = name.replace(ILLEGAL, "_").trim().replace(Regex("""[. ]+$"""), "")
    if (out.length > MAX_LEN) {
        val dot = out.lastIndexOf('.')
        val ext = if (dot > 0 && out.length - dot <= 16) out.substring(dot) else ""
        var cut = MAX_LEN - ext.length
        if (Character.isHighSurrogate(out[cut - 1])) cut-- // keep emoji whole
        out = out.substring(0, cut).trimEnd() + ext
    }
    return out.ifEmpty { "download" }
}

/** A name that `exists` rejects gets a counter: "a.mp4" → "a (1).mp4" → "a (2).mp4". */
fun uniqueName(fileName: String, exists: (String) -> Boolean): String {
    if (!exists(fileName)) return fileName
    val dot = fileName.lastIndexOf('.')
    val base = if (dot > 0) fileName.substring(0, dot) else fileName
    val ext = if (dot > 0) fileName.substring(dot) else ""
    var i = 1
    while (exists("$base ($i)$ext")) i++
    return "$base ($i)$ext"
}
