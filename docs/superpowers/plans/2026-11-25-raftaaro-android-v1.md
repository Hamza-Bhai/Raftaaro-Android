# Raftaaro for Android v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the prototype into Raftaaro v1 for Android: share-to-download with a quality sheet, playlists, multi-part file downloads, a persistent background queue, in-app sign-ins, self-updating yt-dlp, ready for F-Droid and GitHub Releases.

**Architecture:** One `app` module. Pure Kotlin logic ported from the desktop app lives in `core/` and is unit-tested on the JVM. `engine/` wraps youtubedl-android, `http/` does multi-part downloads with OkHttp, `data/` holds Room + DataStore, `download/` is a foreground service that runs the queue, `ui/` is Compose (main screen, settings, share sheet, sign-in). A tiny manual DI container (`AppGraph`) wires them.

**Tech Stack:** Kotlin 2.0.21, AGP 8.7.3, Gradle 8.10.2, Compose BOM 2024.10.01 + Material 3, youtubedl-android 0.18.1 (library + ffmpeg), Room 2.6.1 + KSP 2.0.21-1.0.27, DataStore Preferences 1.1.1, OkHttp 4.12.0 (+ mockwebserver for tests), kotlinx-serialization-json 1.7.3, Coil 2.7.0, navigation-compose 2.8.4, lifecycle-service 2.8.3, JUnit 4.13.2.

**Spec:** `docs/superpowers/specs/2026-11-25-raftaaro-android-v1-design.md`

**Desktop reference:** `D:\Projects\Raftaaro App` (repo `Hamza-Bhai/Raftaaro-Downloader`). "Port X" below means: same behaviour as that file, and its matching `tests/*.test.ts` cases become Kotlin tests with the same names and values.

## Global Constraints

- applicationId and namespace: `io.github.hamzabhai.raftaaro`; Kotlin root package the same.
- minSdk 26, targetSdk 35, compileSdk 35; ABIs arm64-v8a, armeabi-v7a, x86_64.
- English only. No translations.
- No Google Play Services, Firebase, analytics, ads, trackers or closed-source libraries anywhere in the dependency tree.
- License GPL-3.0 (forced by youtubedl-android; owner agreed). The desktop app stays MIT.
- YouTube pacing `--sleep-requests 1 --sleep-interval 3 --max-sleep-interval 8`; max 1 YouTube job at a time; rate-limit cooldown 10 minutes; max 2 automatic retries.
- Defaults: 3 parallel downloads (range 1–5), 8 parts per file (range 1–16), clipboard prompt on, sign-ins used when present.
- Default folders: `Download/Raftaaro/Videos`, `Download/Raftaaro/Audio`, `Download/Raftaaro/Files`.
- yt-dlp update check at most once per 24 h, STABLE channel.
- Foreground service type `dataSync`; ask `POST_NOTIFICATIONS` on the first download, not at launch.
- Build/test on this machine: `JAVA_HOME=C:/Users/HP/android-dev/jdk/current`, SDK in `local.properties`. Unit tests: `./gradlew.bat testDebugUnitTest`. Device tests (phone over wireless adb): `./gradlew.bat connectedDebugAndroidTest`.
- Commit after every task with a `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` trailer. Do not push without the owner's OK.

## Review Focus

1. **Shared text that is not just a URL** (Instagram/Facebook share "Look at this https://… via @x", or no link at all) → the first link is used; with none, the sheet says "No link found in what you shared." Test in Task 2 (`Links`) and Task 12.
2. **Phone restart or app killed mid-download** → the job shows Paused and resuming continues from the partial data, not from zero. Tests in Task 6 (queue rules), Task 8 (HTTP resume), Task 9 (`--continue` + same work dir).
3. **Network drops during a download** → the job ends in Error with a readable message and a Retry action within ~30 s; it never hangs at a frozen percentage. Test in Task 8 (connection reset → error) and OkHttp read timeout 30 s.
4. **Same video downloaded twice / name collision** → the second file is saved as `Name (1).mp4`, never overwriting. Test in Task 10 (legacy path) — MediaStore renames by itself on API 29+.
5. **Titles with emoji, `/`, `:` or 300 characters** → saved under a clean name ≤ 200 chars that keeps its extension. Test in Task 2 (`FileNames`).

---

## File Structure

```
app/src/main/java/io/github/hamzabhai/raftaaro/
  App.kt                     Application; builds AppGraph; warms the engine
  AppGraph.kt                manual DI container (db, settings, engine, http, saver, queue)
  core/                      pure Kotlin, no Android imports
    Models.kt                Job, JobKind, JobStatus, VideoChoice, MediaType, AudioFormat,
                             YtFormat, VideoInfo, PlaylistInfo, PlaylistEntry, QualityOption, Settings
    Links.kt                 extractFirstUrl, isDownloadableUrl, isYouTubeUrl, usesSiteLogin, isVideoSiteUrl
    FileNames.kt             sanitizeFileName, uniqueName
    Ranges.kt                ByteRange, splitRanges
    ByteFormat.kt            formatBytes, formatSpeed, formatEta
    Formats.kt               buildQualityOptions, buildFormatSelector
    YtOutput.kt              parseProgress, classifyYtDlpError, RATE_LIMIT_MESSAGE, parseProbe
    YtArgs.kt                commonArgs, probeArgs, downloadArgs
    Cookies.kt               SiteLogin, toNetscapeCookies
    UpdatePolicy.kt          shouldUpdateYtDlp
    QueueRules.kt            jobsToStart, afterFailure, onAppStart
  engine/
    YtDlpEngine.kt           init, probe, download, cancel, update, version
  http/
    HttpDownload.kt          multi-part download with resume
  data/
    JobEntity.kt, JobDao.kt, AppDatabase.kt, JobRepository.kt
    SettingsStore.kt         DataStore-backed Settings flow
  download/
    Saver.kt                 move finished file to MediaStore / public dir / SAF folder
    Runners.kt               Runner interface, VideoRunner, FileRunner
    DownloadService.kt       foreground service, scheduler loop, notification
    Notifications.kt         channel + notification builders
  ui/
    theme/Theme.kt
    main/MainActivity.kt, MainScreen.kt, DownloadCard.kt, MainViewModel.kt
    share/ShareActivity.kt, ShareSheet.kt, ShareViewModel.kt
    settings/SettingsScreen.kt, SettingsViewModel.kt
    signin/SignInActivity.kt
app/src/test/java/io/github/hamzabhai/raftaaro/...         JVM tests (core, http)
app/src/androidTest/java/io/github/hamzabhai/raftaaro/...  device tests (data, engine, saver)
fastlane/metadata/android/en-US/...                        F-Droid listing
.github/workflows/ci.yml, release.yml
```

The prototype files `app/src/main/java/app/raftaaro/*` are deleted in Task 1 (their working pieces are re-done in Tasks 9–12).

---

### Task 1: Project setup — new id, dependencies, icon, ABI splits

**Files:**
- Modify: `build.gradle.kts`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/io/github/hamzabhai/raftaaro/App.kt`, `ui/main/MainActivity.kt` (placeholder screen "Raftaaro"), `ui/theme/Theme.kt`, adaptive icon `res/mipmap-anydpi-v26/ic_launcher.xml` + foreground PNGs generated from `D:\Projects\Raftaaro App\resources\icon.png`
- Delete: `app/src/main/java/app/raftaaro/`
- Test: `app/src/test/java/io/github/hamzabhai/raftaaro/SmokeTest.kt`

- [ ] **Step 1:** Add plugins `com.google.devtools.ksp` 2.0.21-1.0.27 and `org.jetbrains.kotlin.plugin.serialization` 2.0.21; add every dependency in the Tech Stack line; `testImplementation` junit, okhttp mockwebserver, kotlinx-coroutines-test; `androidTestImplementation` androidx.test.ext:junit, androidx.test:runner, compose ui-test-junit4.
- [ ] **Step 2:** Set ids, sdk levels and ABIs per Global Constraints. ABI splits: `splits.abi { isEnable = true; reset(); include("arm64-v8a","armeabi-v7a","x86_64"); isUniversalApk = true }`. versionCode per ABI = `base * 10 + {armeabi-v7a:1, arm64-v8a:2, x86_64:3}` (universal keeps `base * 10`), via `androidComponents.onVariants`. Keep `jniLibs.useLegacyPackaging = true`.
- [ ] **Step 3:** Manifest: permissions `INTERNET`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `WRITE_EXTERNAL_STORAGE` with `android:maxSdkVersion="28"`; `android:requestLegacyExternalStorage="true"`; app theme follows system dark mode.
- [ ] **Step 4:** `SmokeTest.addition_works()` asserting `2 + 2 == 4`; run `./gradlew.bat testDebugUnitTest assembleDebug`. Expected: BUILD SUCCESSFUL, 3 split APKs + universal in `app/build/outputs/apk/debug/`.
- [ ] **Step 5:** Install the arm64 APK on the phone, confirm it opens with the new icon. Commit `chore: v1 project setup`.

### Task 2: Core models and small helpers

**Files:**
- Create: `core/Models.kt`, `core/Links.kt`, `core/FileNames.kt`, `core/Ranges.kt`, `core/ByteFormat.kt`
- Test: `core/LinksTest.kt`, `core/FileNamesTest.kt`, `core/RangesTest.kt`, `core/ByteFormatTest.kt`

**Interfaces — Produces:**
```kotlin
enum class JobKind { FILE, VIDEO }
enum class JobStatus { QUEUED, PROBING, DOWNLOADING, MERGING, PAUSED, DONE, ERROR }
enum class MediaType { VIDEO, AUDIO }
enum class AudioFormat { MP3, M4A }
data class VideoChoice(val media: MediaType, val height: Int?, /* null = best */ val audioFormat: AudioFormat? = null)
data class Job(val id: String, val kind: JobKind, val url: String, val title: String, val thumbnail: String?,
  val choice: VideoChoice?, val folder: String?, /* playlist subfolder name */ val status: JobStatus,
  val totalBytes: Long?, val receivedBytes: Long, val speedBps: Long, val etaSec: Long?, val error: String?,
  val attempts: Int, val retryAt: Long?, val createdAt: Long, val savedUri: String?, val fileName: String?)
data class YtFormat(val formatId: String, val ext: String, val height: Int?, val vcodec: String, val acodec: String,
  val filesize: Long?, val filesizeApprox: Long?, val tbr: Double?)
data class VideoInfo(val url: String, val title: String, val durationSec: Int?, val uploader: String?, val thumbnail: String?, val formats: List<YtFormat>)
data class PlaylistEntry(val url: String, val title: String, val durationSec: Int?)
data class PlaylistInfo(val url: String, val title: String, val entries: List<PlaylistEntry>)
data class QualityOption(val height: Int?, val label: String, val estBytes: Long?, val audioFormat: AudioFormat? = null)
data class Settings(val videosDir: String?, val audioDir: String?, val filesDir: String?, /* null = default, else SAF tree URI */
  val partsPerFile: Int = 8, val maxConcurrent: Int = 3, val useSignIns: Boolean = true, val clipboardPrompt: Boolean = true,
  val lastYtDlpUpdate: Long? = null, val batteryTipShown: Boolean = false)
fun extractFirstUrl(text: String): String?
fun isDownloadableUrl(text: String): Boolean
fun isYouTubeUrl(url: String): Boolean
fun usesSiteLogin(url: String): Site?          // Site enum defined in Cookies.kt (Task 5); return null for other hosts
fun isVideoSiteUrl(url: String): Boolean
fun sanitizeFileName(name: String): String     // illegal on Android/FAT: <>:"/\|?* and control chars
fun uniqueName(fileName: String, exists: (String) -> Boolean): String
data class ByteRange(val start: Long, val end: Long) // end inclusive
fun splitRanges(size: Long, parts: Int): List<ByteRange>
fun formatBytes(bytes: Long?): String; fun formatSpeed(bps: Long): String; fun formatEta(sec: Long?): String
```

- [ ] **Step 1: Write the failing tests** — port `links.test.ts`, `filename.test.ts`, `ranges.test.ts`, `format.test.ts` (same names, same values). Drop the Windows reserved-name case. Add:
```kotlin
@Test fun `finds the link in an Instagram share text`() =
  assertEquals("https://www.instagram.com/reel/abc/", extractFirstUrl("Look at this https://www.instagram.com/reel/abc/ via @x"))
@Test fun `returns null for share text without a link`() = assertNull(extractFirstUrl("hello there"))
@Test fun `cleans emoji slash and colon titles and keeps the extension under 200 chars`() {
  val out = sanitizeFileName("🎵 a/b: c " + "x".repeat(300) + ".mp4")
  assertTrue(out.length <= 200); assertTrue(out.endsWith(".mp4")); assertFalse(out.contains('/') || out.contains(':'))
}
@Test fun `uniqueName adds a counter`() = assertEquals("a (2).mp4", uniqueName("a.mp4") { it == "a.mp4" || it == "a (1).mp4" })
```
- [ ] **Step 2:** Run `./gradlew.bat testDebugUnitTest --tests "*core*"`. Expected: FAIL (unresolved references).
- [ ] **Step 3:** Implement the files (port of `links.ts`, `filename.ts`, `ranges.ts`, `format.ts`).
- [ ] **Step 4:** Run the same command. Expected: PASS.
- [ ] **Step 5:** Commit `feat(core): models, links, file names, ranges, byte format`.

### Task 3: Quality options and format selector

**Files:** Create `core/Formats.kt`; Test `core/FormatsTest.kt`

**Interfaces — Produces:**
```kotlin
fun buildQualityOptions(info: VideoInfo, media: MediaType): List<QualityOption>
fun buildFormatSelector(choice: VideoChoice): List<String>
```

- [ ] **Step 1:** Port all `formats.test.ts` cases. Labels exactly: `"⭐ Best (1080p)"`, `"2160p (4K)"`, `"1440p (2K)"`, `"4320p (8K)"`, `"MP3"`, `"M4A"`. Selector for height 720 must equal `listOf("-f","bv*[height<=720]+ba/b[height<=720]/bv*+ba/b","-S","res,proto,+codec:avc:m4a","--merge-output-format","mp4")`; MP3 = `listOf("-f","ba/b","-x","--audio-format","mp3","--audio-quality","0")`; M4A = `listOf("-f","ba[ext=m4a]/ba/b","-x","--audio-format","m4a")`.
- [ ] **Step 2:** Run `--tests "*FormatsTest*"`, expect FAIL.
- [ ] **Step 3:** Implement as a port of `formats.ts`.
- [ ] **Step 4:** Run, expect PASS.
- [ ] **Step 5:** Commit `feat(core): quality options and format selector`.

### Task 4: yt-dlp arguments and output parsing

**Files:** Create `core/YtOutput.kt`, `core/YtArgs.kt`; Test `core/YtOutputTest.kt`, `core/YtArgsTest.kt`, fixtures `app/src/test/resources/probe/{video.json,playlist.json,channel.json}` (copy from desktop `tests/fixtures` where present, else trimmed real `-J` output)

**Interfaces — Produces:**
```kotlin
const val RATE_LIMIT_MESSAGE = "YouTube asked us to slow down. Raftaaro will retry automatically."
const val OUTDATED_MESSAGE = "yt-dlp is out of date — update it in Settings"
data class YtProgress(val downloaded: Long, val totalBytes: Long?, val estimated: Boolean, val speedBps: Long?, val etaSec: Long?)
const val PROGRESS_TEMPLATE: String   // same text as desktop, prefix "RFTP "
fun parseProgress(line: String): YtProgress?
fun classifyYtDlpError(stderr: String): String
sealed interface ProbeResult { data class Video(val info: VideoInfo): ProbeResult
  data class Playlist(val info: PlaylistInfo): ProbeResult; data class OpenTab(val url: String): ProbeResult }
fun parseProbe(url: String, json: String): ProbeResult   // kotlinx.serialization JsonObject, ignoreUnknownKeys
fun probeArgs(url: String, cookieFile: String?): List<String>
fun downloadArgs(url: String, choice: VideoChoice?, workDir: String, cookieFile: String?): List<String>
```
`commonArgs` (private): `--encoding utf-8 --no-warnings`, `--cookies <file>` when given, YouTube pacing when `isYouTubeUrl`. `probeArgs`: `-J --flat-playlist` (+ `--no-playlist` for URLs with `v=` or `youtu.be/`). `downloadArgs`: common + `--newline --continue --progress-template download:<PROGRESS_TEMPLATE> -o <workDir>/%(title).150B [%(id)s].%(ext)s` + `buildFormatSelector` (choice null → plain `-f b`); `--no-playlist` as for probe. The Chrome-cookie error branch from desktop is dropped (no Chrome on Android).

- [ ] **Step 1:** Port `ytdlp.test.ts` cases for `parseProgress`, `classifyYtDlpError` (minus the Chrome one), probe parsing (video, playlist entry URLs, channel → `OpenTab` of the `/videos` tab), YouTube-only pacing, cookies only when a file is given. Add `downloadArgs contains --continue and the work dir template`.
- [ ] **Step 2:** Run `--tests "*Yt*"`, expect FAIL.
- [ ] **Step 3:** Implement.
- [ ] **Step 4:** Run, expect PASS.
- [ ] **Step 5:** Commit `feat(core): yt-dlp args, progress and error parsing`.

### Task 5: Cookie file and update policy

**Files:** Create `core/Cookies.kt`, `core/UpdatePolicy.kt`; Test `core/CookiesTest.kt`, `core/UpdatePolicyTest.kt`

**Interfaces — Produces:**
```kotlin
enum class Site(val label: String, val loginUrl: String, val cookieDomains: List<String>) {
  YOUTUBE("YouTube", "https://accounts.google.com/ServiceLogin?continue=https://m.youtube.com", listOf("youtube.com","google.com")),
  INSTAGRAM("Instagram", "https://www.instagram.com/accounts/login/", listOf("instagram.com")),
  FACEBOOK("Facebook", "https://m.facebook.com/login/", listOf("facebook.com")) }
/** cookieHeader is CookieManager.getCookie() output: "a=1; b=2". */
fun toNetscapeCookies(site: Site, cookieHeaders: Map<String, String?>): String   // key = domain
fun shouldUpdateYtDlp(lastUpdate: Long?, now: Long): Boolean
```
Each cookie becomes `.<domain>\tTRUE\t/\tTRUE\t0\t<name>\t<value>`; names/values with tab/CR/LF are dropped; file starts `# Netscape HTTP Cookie File`. `usesSiteLogin` (Task 2) maps youtube.com/youtu.be → YOUTUBE, instagram.com → INSTAGRAM, facebook.com/fb.watch → FACEBOOK.

- [ ] **Step 1:** Tests: port `update-policy.test.ts` (3 cases); cookies: writes header + one line per pair, drops a value containing `\t`, ignores null headers.
- [ ] **Step 2:** Run, expect FAIL. **Step 3:** Implement. **Step 4:** Run, expect PASS.
- [ ] **Step 5:** Commit `feat(core): cookie file and update policy`.

### Task 6: Queue rules

**Files:** Create `core/QueueRules.kt`; Test `core/QueueRulesTest.kt`

**Interfaces — Produces:**
```kotlin
/** Ids of queued jobs to start now, oldest first. */
fun jobsToStart(jobs: List<Job>, runningIds: Set<String>, maxConcurrent: Int, now: Long): List<String>
/** Next state of a job whose runner failed with `message`. */
fun afterFailure(job: Job, message: String, now: Long): Job
/** On app/service start: PROBING/DOWNLOADING/MERGING become PAUSED with speed 0 and no ETA. */
fun onAppStart(jobs: List<Job>): List<Job>
/** Earliest retryAt among queued jobs in the future, for the wake-up timer. */
fun nextWake(jobs: List<Job>, now: Long): Long?
```
Rules (port `queue.ts` `pump` and failure handling): at most `maxConcurrent` running; at most 1 YouTube running; no YouTube job starts while any queued job's `retryAt > now`; `afterFailure` with `RATE_LIMIT_MESSAGE` and `attempts + 1 <= 2` → QUEUED with `retryAt = now + 600_000`; otherwise ERROR with the message.

- [ ] **Step 1:** Port `queue.test.ts`: "runs at most maxConcurrent jobs", "marks interrupted downloads paused on startup", "runs one YouTube download at a time while other downloads continue", "backs off for 10 minutes after a rate limit and then retries by itself", "gives up after two automatic retries".
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS.
- [ ] **Step 5:** Commit `feat(core): queue rules`.

### Task 7: Storage — Room jobs and DataStore settings

**Files:** Create `data/JobEntity.kt`, `data/JobDao.kt`, `data/AppDatabase.kt`, `data/JobRepository.kt`, `data/SettingsStore.kt`; Test `androidTest/data/JobRepositoryTest.kt`, `androidTest/data/SettingsStoreTest.kt`

**Interfaces — Produces:**
```kotlin
class JobRepository(dao: JobDao) {
  val jobs: Flow<List<Job>>                     // newest first
  suspend fun all(): List<Job>; suspend fun get(id: String): Job?
  suspend fun add(job: Job); suspend fun update(job: Job); suspend fun updateAll(jobs: List<Job>)
  suspend fun delete(id: String) }
class SettingsStore(context: Context) {
  val settings: Flow<Settings>; suspend fun current(): Settings
  suspend fun update(change: (Settings) -> Settings) }
```
`JobEntity` stores `choice` as three nullable columns. DB name `raftaaro.db`, version 1, `exportSchema = true` with schemas in `app/schemas`.

- [ ] **Step 1:** Device tests: "round-trips jobs", "jobs flow emits newest first", "settings has defaults on a fresh install" (values from Global Constraints), "saves partial settings".
- [ ] **Step 2:** Run `./gradlew.bat connectedDebugAndroidTest --tests "*data*"` (phone connected). Expected FAIL.
- [ ] **Step 3:** Implement. **Step 4:** Expected PASS.
- [ ] **Step 5:** Commit `feat(data): Room jobs and DataStore settings`.

### Task 8: Multi-part HTTP download

**Files:** Create `http/HttpDownload.kt`; Test `http/HttpDownloadTest.kt` (JVM, MockWebServer)

**Interfaces — Produces:**
```kotlin
data class HttpProgress(val received: Long, val total: Long?, val speedBps: Long)
data class HttpResult(val file: File, val fileName: String)
class HttpDownload(client: OkHttpClient, val url: String, val workDir: File, val parts: Int) {
  /** Suspends until done; resumes from workDir if a previous run left parts. Cancel the coroutine to pause. */
  suspend fun run(onProgress: (HttpProgress) -> Unit): HttpResult }
```
Port `http-download.ts`: HEAD-less probe via `GET` with `Range: bytes=0-0` to learn size and range support; `meta.json` (url, total, ranges) in `workDir`; restart when URL or total changed; single stream when ranges unsupported; join parts into `workDir/<fileName>`; filename from `Content-Disposition` then URL path, through `sanitizeFileName`; progress every 250 ms, speed over a 3 s window. OkHttp client: follow redirects (max 10 default), connect 15 s, read 30 s timeouts.

- [ ] **Step 1:** Port the 8 `http-download.test.ts` cases (8 parts byte-equal, Content-Disposition name, redirect, no-range fallback, resume from existing parts, restart when size changed, two downloads of the same URL in separate work dirs stay apart, 404 → exception and no output file). Add `connection reset mid-body throws IOException` (MockWebServer `SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY`).
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS.
- [ ] **Step 5:** Commit `feat(http): multi-part download with resume`.

### Task 9: yt-dlp engine

**Files:** Create `engine/YtDlpEngine.kt`; Test `androidTest/engine/YtDlpEngineTest.kt`

**Interfaces — Consumes:** `probeArgs`, `downloadArgs`, `parseProbe`, `parseProgress`, `classifyYtDlpError`, `shouldUpdateYtDlp`, `OUTDATED_MESSAGE`.
**Produces:**
```kotlin
class YtDlpEngine(context: Context) {
  suspend fun ensureReady()                                   // YoutubeDL.init + FFmpeg.init once, on IO
  suspend fun probe(url: String, cookieFile: File?): ProbeResult   // follows OpenTab once
  /** Runs in workDir with --continue; returns the finished file. Throws YtDlpError(message). */
  suspend fun download(jobId: String, url: String, choice: VideoChoice?, workDir: File, cookieFile: File?,
                       onProgress: (YtProgress) -> Unit, onMerging: () -> Unit): File
  fun cancel(jobId: String)                                   // destroyProcessById(jobId)
  suspend fun updateIfDue(settings: SettingsStore)            // shouldUpdateYtDlp → update STABLE, store time
  suspend fun updateNow(): String                             // "yt-dlp updated to …" / "yt-dlp is already up to date"
  fun version(): String? }
class YtDlpError(message: String) : Exception(message)
```
Pass args via `YoutubeDLRequest(url).addCommands(args)`; read our `RFTP` lines from the callback's line argument; `[Merger]` / `[ExtractAudio]` lines → `onMerging`. On `OUTDATED_MESSAGE`, `download` calls `updateNow()` once and retries once. Finished file = newest non-`.part`/`.ytdl` file in `workDir`.

- [ ] **Step 1:** Device tests (network needed): "probes a short public video" (`https://www.youtube.com/watch?v=jNQXAC9IVRw` → Video, title "Me at the zoo"), "downloads it as MP3 into the work dir", "a second run in the same work dir finishes without re-downloading" (yt-dlp reports already downloaded).
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS.
- [ ] **Step 5:** Commit `feat(engine): yt-dlp probe, download, update`.

### Task 10: Saving finished files

**Files:** Create `download/Saver.kt`; Test `androidTest/download/SaverTest.kt`

**Interfaces — Produces:**
```kotlin
enum class Bucket { VIDEOS, AUDIO, FILES }
class Saver(context: Context) {
  /** Moves file into the user-visible folder for bucket (or the SAF tree in settings), under subfolder if given.
      Returns the content URI string. Deletes the source on success. */
  suspend fun save(file: File, bucket: Bucket, subfolder: String?, settings: Settings): String
  fun open(uri: String): Intent; fun share(uri: String): Intent
  suspend fun delete(uri: String): Boolean }
```
API 29+: MediaStore Downloads with `RELATIVE_PATH = Download/Raftaaro/<Videos|Audio|Files>[/<subfolder>]`, `IS_PENDING` 1 → 0. API 26–28: public `Download/Raftaaro/...` with `uniqueName`, then `MediaScannerConnection.scanFile`. SAF tree set: `DocumentFile.fromTreeUri(...).createFile` (DocumentsProvider renames on collision). Bucket from choice: AUDIO media → AUDIO, video → VIDEOS, FILE jobs → FILES.

- [ ] **Step 1:** Device tests: "saves into Download/Raftaaro/Videos and returns a readable uri", "saving the same name twice keeps both files" (both URIs readable, different display names), "saves a playlist video into its subfolder".
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS.
- [ ] **Step 5:** Commit `feat(download): save finished files to shared storage`.

### Task 11: Download service and queue

**Files:** Create `download/Runners.kt`, `download/DownloadService.kt`, `download/Notifications.kt`, `AppGraph.kt`; Modify `App.kt`, manifest (service `android:foregroundServiceType="dataSync"`)

**Interfaces — Consumes:** Tasks 6–10. **Produces:**
```kotlin
class AppGraph(context: Context) { val jobs: JobRepository; val settings: SettingsStore; val engine: YtDlpEngine
  val http: OkHttpClient; val saver: Saver; val queue: DownloadQueue; fun cookieFile(site: Site): File }
class DownloadQueue(graph: AppGraph, scope: CoroutineScope) {
  suspend fun enqueue(url: String, title: String, kind: JobKind, choice: VideoChoice?, folder: String?, thumbnail: String?): String
  suspend fun pause(id: String); suspend fun resume(id: String); suspend fun retry(id: String)
  suspend fun remove(id: String, deleteFile: Boolean)
  suspend fun pauseAll(reason: String) }
```
`enqueue` starts `DownloadService` (`startForegroundService`). The service: on start applies `onAppStart` once per process; loop = observe jobs + settings, call `jobsToStart`, launch a runner coroutine per id (work dir `filesDir/jobs/<id>/`), write progress to the repository at most every 1 s, on success `saver.save` then DONE with `savedUri`/`fileName` and delete work dir; on failure `afterFailure`; schedule a wake at `nextWake`. Stop foreground + `stopSelf` when nothing is queued or running. `onTimeout(startId, fgsType)` → `pauseAll("Paused by Android's background limit — tap to resume")`. Notification: one ongoing summary ("2 downloading · 45%") with Pause all, plus a "Download finished: <title>" notification per completed job opening the file. `remove(deleteFile=true)` deletes the saved URI and the work dir.

- [ ] **Step 1:** Device test `DownloadQueueTest`: "a file job runs to DONE and its file is readable" (a 2 MB body served by MockWebServer running inside the test on the device), "pause then resume finishes with the right byte count".
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS.
- [ ] **Step 5:** Manual on phone: start a 200 MB file download, turn the screen off for 2 minutes, it keeps going; force-stop the app, reopen → Paused, Resume continues from where it stopped.
- [ ] **Step 6:** Commit `feat(download): foreground service and queue`.

### Task 12: Share sheet — single video and plain files

**Files:** Create `ui/share/ShareActivity.kt`, `ui/share/ShareSheet.kt`, `ui/share/ShareViewModel.kt`; Modify manifest (ShareActivity with `SEND text/plain` and `VIEW http/https` filters, translucent no-title theme, `excludeFromRecents`, `taskAffinity=""`); Test `androidTest/ui/ShareSheetTest.kt`

**Interfaces — Produces:**
```kotlin
sealed interface ShareState { data object Loading: ShareState; data class NoLink(val text: String): ShareState
  data class Video(val info: VideoInfo, val video: List<QualityOption>, val audio: List<QualityOption>): ShareState
  data class Playlist(val info: PlaylistInfo): ShareState
  data class File(val url: String, val name: String, val size: Long?): ShareState
  data class Failed(val message: String, val signIn: Site?): ShareState }
```
Flow: extract link (`extractFirstUrl`) → Loading shown in the first frame → if `isVideoSiteUrl` or URL path has no known file extension → `engine.probe` (cookie file when `useSignIns` and `usesSiteLogin`); if probe fails with yt-dlp's "Unsupported URL" → plain file. Known file extensions: zip rar 7z apk pdf exe msi dmg iso mp3 mp4 mkv jpg png gif txt doc docx xls xlsx ppt pptx. File state fetches name/size with `HttpDownload`'s probe request. Tapping a quality → `queue.enqueue(...)`, toast "Downloading in Raftaaro", `finish()`. First download ever on API 33+: request `POST_NOTIFICATIONS` before enqueueing (enqueue either way). Failed with a sign-in message → button "Sign in to <Site>" opens `SignInActivity` (Task 16).

Copy: NoLink → "No link found in what you shared."

- [ ] **Step 1:** Compose UI tests with a fake state: video state lists "⭐ Best (1080p)" first and "MP3" last; NoLink shows the copy above; tapping a row calls the enqueue callback with that `VideoChoice`.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS.
- [ ] **Step 5:** Manual: share from the YouTube, Instagram and Facebook apps and from Chrome; sheet appears over the app, download lands in `Download/Raftaaro/Videos`.
- [ ] **Step 6:** Commit `feat(ui): share sheet for videos and files`.

### Task 13: Share sheet — playlists and channels

**Files:** Modify `ui/share/ShareSheet.kt`, `ui/share/ShareViewModel.kt`; Test `androidTest/ui/PlaylistSheetTest.kt`

Playlist state shows title, "N videos", a checklist (all ticked, "Select all" toggle), a quality row (Best, 1080p, 720p, 480p, MP3, M4A — heights are maximums since entries are not probed), and **Download N**. Enqueue one job per ticked entry with `folder = sanitizeFileName(playlist title)` and the entry title.

- [ ] **Step 1:** UI test: unticking one of three entries makes the button read "Download 2" and enqueues two jobs with the playlist folder.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS.
- [ ] **Step 5:** Commit `feat(ui): playlist selection`.

### Task 14: Main screen

**Files:** Create `ui/main/MainScreen.kt`, `ui/main/DownloadCard.kt`, `ui/main/MainViewModel.kt`; Modify `ui/main/MainActivity.kt`; Test `androidTest/ui/MainScreenTest.kt`

Top bar "Raftaaro" with Settings icon. Link box with Paste and Download → opens the same sheet content in a `ModalBottomSheet` (reuse `ShareSheet` + `ShareViewModel`). Filter chips **Active** / **Finished**. Card: thumbnail (Coil), title, status text, progress bar, `formatBytes`/`formatSpeed`/`formatEta`; actions by status — DOWNLOADING/QUEUED: Pause, Cancel; PAUSED: Resume, Cancel; ERROR: Retry, Remove, error text; DONE: Open, Share, Delete (dialog with "Also delete the file" checkbox). Clipboard prompt: on `onResume` when `clipboardPrompt` is on and the clipboard holds a URL (`isDownloadableUrl`) different from the last one offered (kept in memory), show snackbar "Download this link?" with action **Download**. Empty state: "Share a video to Raftaaro from any app, or paste a link above." Battery tip: when `onAppStart` paused at least one job (the app was killed mid-download) and the tip was never shown, show a one-time dialog "Your phone stopped a download in the background. Allow Raftaaro to run without battery restrictions?" with **Open settings** (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`) and **Not now**; remember it was shown in `SettingsStore`.

- [ ] **Step 1:** UI tests with fake jobs: a PAUSED card shows Resume; an ERROR card shows its message and Retry; the Finished filter hides active jobs.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS.
- [ ] **Step 5:** Commit `feat(ui): main screen and downloads list`.

### Task 15: Settings screen

**Files:** Create `ui/settings/SettingsScreen.kt`, `ui/settings/SettingsViewModel.kt`; Test `androidTest/ui/SettingsScreenTest.kt`

Rows: Videos/Audio/Files folder (shows "Download/Raftaaro/…" or the picked folder; **Change** uses `OpenDocumentTree` and `takePersistableUriPermission`; **Reset**); Parallel downloads slider 1–5; Parts per file slider 1–16; "Use my sign-ins for downloads" switch; per-site rows (Task 16 fills them); "Offer to download copied links" switch; yt-dlp version, "Last checked …", **Update now** (shows the `updateNow()` text); About: app version, licenses list (yt-dlp Unlicense, FFmpeg LGPL 2.1+, Python PSF, QuickJS MIT, youtubedl-android GPL-3.0), source link, "Download only content you have the right to download."

- [ ] **Step 1:** UI test: moving the parallel slider to 5 stores `maxConcurrent = 5`.
- [ ] **Step 2:** FAIL. **Step 3:** Implement. **Step 4:** PASS.
- [ ] **Step 5:** Commit `feat(ui): settings`.

### Task 16: In-app sign-in

**Files:** Create `ui/signin/SignInActivity.kt`; Modify `ui/settings/SettingsScreen.kt`, manifest

`SignInActivity` takes extra `site` (`Site.name`), shows a top bar "Sign in to <label>" with **Done**, and a `WebView` loading `site.loginUrl` (JavaScript on, `allowFileAccess = false`, `allowContentAccess = false`, third-party cookies on). Done → for each `site.cookieDomains` read `CookieManager.getCookie("https://<domain>")` and `"https://www.<domain>"`, write `toNetscapeCookies` to `graph.cookieFile(site)` (`filesDir/cookies/<site>.txt`), finish. Signed in = file exists and has at least one cookie line. Sign out = delete file + `CookieManager.removeAllCookies` is NOT used (would sign out all sites); instead expire each cookie of that site's domains by setting `name=; Max-Age=0`.

- [ ] **Step 1:** JVM test already covers the file format (Task 5). Manual: sign in to Instagram, share a reel that fails signed-out → it downloads; sign out → the row shows Sign in again and the file is gone.
- [ ] **Step 2:** Commit `feat(ui): in-app sign-in for YouTube, Instagram, Facebook`.

### Task 17: Engine warm-up, auto-update and first run

**Files:** Modify `App.kt`, `engine/YtDlpEngine.kt`

`App.onCreate`: build `AppGraph`; on an app-scoped IO coroutine `engine.ensureReady()` then `engine.updateIfDue(settings)`. `ShareViewModel` awaits `ensureReady()` before probing (with the Loading state visible). A YouTube probe on first run waits up to 8 s for an in-flight update, then proceeds.

- [ ] **Step 1:** JVM test in `UpdatePolicyTest` already covers the schedule. Manual: clear app data, open the app with network → Settings shows a yt-dlp version newer than 2025.11.12 within a minute.
- [ ] **Step 2:** Commit `feat(engine): warm-up and daily yt-dlp update`.

### Task 18: Release builds, CI and F-Droid metadata

**Files:** Modify `app/build.gradle.kts` (release signing from `local.properties` keys `release.storeFile`, `release.storePassword`, `release.keyAlias`, `release.keyPassword`, only when present; R8 minify + shrink with keep rules for youtubedl-android's Jackson mappers `-keep class com.yausername.** { *; }` and kotlinx.serialization), `README.md`; Create `.github/workflows/ci.yml` (on push/PR: JDK 17, `./gradlew testDebugUnitTest assembleDebug`), `.github/workflows/release.yml` (on tag `v*`: decode keystore from secret `RELEASE_KEYSTORE_BASE64`, write signing values from secrets, `assembleRelease`, attach all APKs to the GitHub Release), `fastlane/metadata/android/en-US/{title.txt,short_description.txt,full_description.txt,changelogs/<versionCode>.txt,images/icon.png,images/phoneScreenshots/1.png…}`

- [ ] **Step 1:** The owner creates the release keystore themself (the plan only documents the `keytool` command in README); Claude never sees the passwords.
- [ ] **Step 2:** Run `./gradlew.bat assembleRelease` locally with a throwaway debug-signed config if no key yet. Expected: APKs for 3 ABIs + universal; arm64 under ~70 MB.
- [ ] **Step 3:** Install the arm64 release APK on the phone; share flow, one video, one MP3, one file all work (R8 did not break the engine).
- [ ] **Step 4:** Commit `build: release builds, CI and F-Droid metadata`.

### Task 19: Final device check

- [ ] **Step 1:** Run all tests: `./gradlew.bat testDebugUnitTest connectedDebugAndroidTest`. Expected: all PASS.
- [ ] **Step 2:** Walk the spec's manual checklist on the phone: share from YouTube, Instagram, Facebook; screen off during a long download; phone restart mid-download; sign-in/out; playlist of 3; file with 16 parts; Settings changes take effect; clipboard prompt.
- [ ] **Step 3:** Write the results into the PR/commit description; anything failing becomes a fix task before release.
