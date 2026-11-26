package io.github.hamzabhai.raftaaro.core

/**
 * yt-dlp's challenge solver keeps each prepared YouTube player (~4 MB) under a name starting
 * "player"; YouTube changes players every few days. Returns the names to delete: all but the
 * `keep` newest (by modification time). Other cache entries are left alone.
 */
fun stalePlayerFiles(files: List<Pair<String, Long>>, keep: Int): List<String> =
    files.filter { it.first.startsWith("player") }.sortedByDescending { it.second }.drop(keep).map { it.first }

/**
 * yt-dlp plugin that turns on the challenge solver's prepared-player cache (off in yt-dlp because
 * the files are large and it does not delete old ones; [stalePlayerFiles] does). With it, solving
 * YouTube's challenge takes ~3 s instead of ~20 s on a phone for every video after the first.
 */
const val PLAYER_CACHE_PLUGIN = """# Raftaaro: keep the challenge solver's prepared YouTube player between runs
from yt_dlp.extractor.youtube.jsc._builtin.ejs import EJSBaseJCP

EJSBaseJCP._ENABLE_PREPROCESSED_PLAYER_CACHE = True
"""

/**
 * Python script: `src dst` copies a yt-dlp zip adding an unchecked hash-based .pyc beside every
 * .py, so Python does not compile yt-dlp on every start (~3-4 s on a phone). The sources stay,
 * so a different Python (bad magic) still runs it from source.
 */
const val PRECOMPILE_SCRIPT = """import sys, zipfile, marshal, importlib.util
src, dst = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(src) as zin, zipfile.ZipFile(dst, 'w', zipfile.ZIP_STORED) as zout:
    for info in zin.infolist():
        data = zin.read(info)
        zout.writestr(info, data)
        if info.filename.endswith('.py'):
            code = compile(data, info.filename, 'exec', dont_inherit=True)
            # flags=1: hash-based, unchecked; the hash is never compared
            pyc = importlib.util.MAGIC_NUMBER + (1).to_bytes(4, 'little') + bytes(8) + marshal.dumps(code)
            zout.writestr(info.filename + 'c', pyc)
print('ok')
"""
