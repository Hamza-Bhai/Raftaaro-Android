# Raftaaro for Android — v1 design

Date: 2026-11-25 · Status: draft for approval

## 1. Intent

Raftaaro is a free, open-source download manager. The [desktop app](https://github.com/Hamza-Bhai/Raftaaro-Downloader) (Electron, Windows) already exists. This project brings it to Android so that **ordinary, non-technical people anywhere in the world can save videos, audio and files on their phone, for free and with no effort**.

The defining moment is: someone is watching a video in Instagram, YouTube, Facebook or any other app and wants to keep it. They tap **Share → Raftaaro → pick a quality**, and that is all. The download runs in the background and the file appears in their gallery. If this path is not fast and dependable, nothing else matters.

## 2. Goals, non-goals, anti-goals

**Goals (v1 ships all of these — the owner's decision)**

1. Share-to-download in four taps, from any app, without leaving that app.
2. Video and audio from YouTube, Instagram and Facebook first, and from any site yt-dlp supports.
3. Quality picker showing only the qualities the video really has, each with an estimated size, plus audio-only MP3 and M4A.
4. Playlists and channels: list the entries, let the user tick which ones to get, save them in a folder named after the playlist.
5. Plain file downloads (zip, apk, pdf…) over 1–16 parallel connections.
6. A queue with a limit on parallel downloads; YouTube downloads run one at a time with pauses and back off automatically when YouTube rate-limits.
7. Pause and resume, surviving app close and phone restart.
8. In-app sign-in to YouTube, Instagram and Facebook so login-only videos work.
9. Downloads continue in the background with a progress notification (pause / cancel).
10. yt-dlp updates itself.
11. A downloads list: running and finished items; open, share, delete.
12. Settings: save folder, parallel downloads, parts per file, sign-ins on/off, clipboard prompt on/off.
13. Clipboard prompt: on opening the app, offer to download a link the user just copied.
14. Distributed on **F-Droid**, with GitHub Releases alongside.

**Non-goals**

- No Urdu or other translations. English only; the app is international.
- No Google Play release (Play rejects YouTube downloaders).
- No browser extension; Share replaces it.
- No built-in video player, editor or converter beyond what yt-dlp/ffmpeg already do while downloading.
- No accounts, cloud sync or server of our own.

**Anti-goals (would make v1 a failure even if it "works")**

- Ads, trackers, analytics, or any Google/closed-source library. The app is free and ad-free; F-Droid would reject it anyway.
- Logins or cookies leaving the phone.
- A share flow that opens the full app, asks questions, or makes the user wait on a blank screen.
- Downloads that silently die when the screen turns off.
- Monetization hooks. Earning may be considered later; v1 leaves no room for it.

## 3. Constraints

- **Platform:** Android 8.0 (API 26) and newer; target the latest stable SDK. Phones are mostly arm64; also ship armeabi-v7a and x86_64.
- **F-Droid:** everything must build from source with free dependencies from Maven Central / Google Maven. No proprietary SDKs. F-Droid will likely tag the app with the "Non-Free Network Services" anti-feature; that is expected.
- **License:** MIT, same as desktop.
- **Engine:** [youtubedl-android](https://github.com/yausername/youtubedl-android) 0.18.x (`io.github.junkfood02.youtubedl-android`), which bundles Python 3.12, yt-dlp, ffmpeg and QuickJS. Verified on a TECNO KG8 (Android 11, arm64) by the prototype in this repo: info, MP4 (merged) and MP3 all worked, and in-app yt-dlp update worked (to 2026.11.23).
- **Bundled yt-dlp is old** (2025.11.12 in the library). YouTube breaks old versions, so the first launch must update it before the first YouTube download where possible.
- **Android background limits:** long transfers need a foreground service (type `dataSync`) with a visible notification. On Android 15+ a `dataSync` service gets about 6 hours per 24 hours; when the system calls `onTimeout`, running jobs must be paused cleanly and marked as such, not lost.
- **Storage:** Android 10+ writes to shared storage through MediaStore; Android 8–9 need `WRITE_EXTERNAL_STORAGE` (declared with `maxSdkVersion="28"`).
- **Notifications:** Android 13+ needs `POST_NOTIFICATIONS`; ask the first time a download starts, not at launch.
- **Respect sites:** keep the desktop's YouTube pacing (`--sleep-requests 1 --sleep-interval 3 --max-sleep-interval 8`) and one-YouTube-at-a-time rule.

## 4. Decisions on how it is made

Decided by the owner: Android native app, all v1 features above, English only, free/ad-free/open-source, F-Droid.

Claude's calls (the owner can overrule):

- **Kotlin + Jetpack Compose + Material 3**, single `app` module, building on the prototype already in this repo.
- **Application id `io.github.hamzabhai.raftaaro`** (replacing the prototype's `app.raftaaro`). It can never change once published on F-Droid, and the `io.github.<owner>` form is tied to a name the owner controls.
- **Defaults:** 3 parallel downloads, 8 parts per file, clipboard prompt on, sign-ins used when present.
- **Dark mode follows the system.**
- **GitHub Releases alongside F-Droid**, because F-Droid builds lag days to weeks.
- **The desktop logic is ported, not reinvented.** These desktop files are the reference and their behaviour should match:
  - `src/main/video/formats.ts` → quality options, size estimates, format selector (`-S res,proto,+codec:avc:m4a`, MP4 merge, MP3 `--audio-quality 0`, M4A).
  - `src/main/video/ytdlp.ts` → probe (`-J --flat-playlist`, `--no-playlist` for watch URLs with `v=`, channel root → Videos tab), progress template, error classification, rate-limit message.
  - `src/shared/links.ts` → URL extraction from shared text, YouTube detection, which sites use the login, video-site vs plain-file routing.
  - `src/main/queue.ts` → job states, max parallel, max 1 YouTube, 10-minute rate-limit cooldown, at most 2 automatic retries, active jobs become paused after a restart.
  - `src/main/http/http-download.ts` + `ranges.ts` → multi-part ranges, `.rpart` folder with `meta.json`, resume when server supports ranges, single stream otherwise, redirects, filename from `Content-Disposition`.
  - `src/main/video/update-policy.ts` → check for a new yt-dlp at most once a day.
  - `src/main/video/cookies.ts` → Netscape cookie file, limited to YouTube/Google/Instagram/Facebook domains.
  - `src/main/util/filename.ts` → filename sanitising and unique paths.

## 5. The app, piece by piece

### 5.1 Share sheet (the main path)

- A separate `ShareActivity` with a translucent theme, so the app the user came from stays visible behind a **bottom sheet**.
- It takes the first http(s) URL in `EXTRA_TEXT` (or `EXTRA_STREAM` text / `ACTION_VIEW` data).
- Routing:
  - **Video site or unknown site** → probe with yt-dlp. While probing, the sheet shows the link and a spinner within the first frame (no blank screen).
  - **Plain file** (probe says it is not media, or the URL ends in a known file extension and a HEAD request returns a non-HTML type) → show file name and size, one **Download** button.
- **Single video result:** thumbnail, title, uploader, duration; a list of qualities (`⭐ Best (1080p)`, `1080p`, `720p`… each with ~size), then `Audio · MP3`, `Audio · M4A`. One tap on a row enqueues the job and closes the sheet with a short toast ("Downloading in Raftaaro").
- **Playlist/channel result:** title, count, a checklist of entries (all ticked), a quality choice applied to all (Best / a max height / MP3 / M4A), and **Download N**.
- **Errors** (sign-in needed, unavailable, unsupported) show in the sheet with one action where it helps (e.g. **Sign in to Instagram**).
- Probing can take several seconds on a cold start because the engine unpacks on first run; initialise the engine in `Application.onCreate` on a background thread so it is usually warm.

### 5.2 Main screen

- Top: a link box with **Paste** and **Download** (same flow as the share sheet, shown in-app).
- Below: the downloads list, newest first. Each card shows thumbnail, title, status, progress bar, speed/ETA, size; actions per state: pause, resume, cancel, retry, open, share, delete (with "also delete file" choice).
- Tabs or a filter for **Active / Finished** (builder's choice).
- Clipboard prompt: when the app comes to the foreground and the clipboard holds a new downloadable URL not offered before, show a snackbar "Download this link?" (off in Settings).

### 5.3 Settings

- Save folders for Videos, Audio, Files. Default `Download/Raftaaro/Videos`, `/Audio`, `/Files`. Optionally a user-picked folder via the system folder picker (SAF tree URI).
- Parallel downloads (1–5, default 3), parts per file (1–16, default 8).
- Sign-ins: YouTube, Instagram, Facebook — each shows signed in / out, with **Sign in** / **Sign out**. A master switch "Use my sign-ins for downloads".
- Clipboard prompt on/off.
- yt-dlp: current version, last checked, **Update now**.
- About: version, licenses (yt-dlp Unlicense, FFmpeg LGPL, Python PSF, QuickJS MIT), source link, responsible-use note.

### 5.4 Downloads engine

- **Job model** in Room, mirroring desktop `Job`: id, kind (file/video), url, title, thumbnail, choice (media, height, audioFormat), save target, status (`queued`, `probing`, `downloading`, `merging`, `paused`, `done`, `error`), total/received bytes, speed, ETA, error, attempts, retryAt, createdAt, playlist folder name, final content URI.
- **DownloadService** (foreground, `dataSync`) runs whenever any job is queued or active, shows one summary notification with progress and per-job pause/cancel actions, and stops itself when the queue is idle. On `onTimeout` (Android 15+) it pauses all running jobs with the reason "Paused by Android's background limit — tap to resume".
- **Queue scheduler** inside the service: start queued jobs up to the parallel limit, never more than one YouTube job, honour `retryAt` for rate-limited jobs (10-minute cooldown, max 2 automatic retries).
- **Video jobs** run yt-dlp through youtubedl-android with a process id per job. Pause/cancel = `destroyProcessById`. Each job has its own work folder under `filesDir/jobs/<id>/` (not cache, so it survives cleanup) and yt-dlp runs with `--continue` so resume reuses `.part` files. Progress uses the desktop's `--progress-template` so bytes, speed and ETA are exact rather than the library's percent.
- **File jobs** use OkHttp with the desktop's multi-part algorithm, writing parts under `filesDir/jobs/<id>/`.
- **Finishing:** copy the result from the work folder to the target (MediaStore on Android 10+, the public folder on 8–9, or the SAF folder), mark it non-pending, store the content URI, delete the work folder. Scan so it appears in the gallery.
- **After a restart** jobs that were active become paused (as on desktop); the user resumes them.

### 5.5 yt-dlp updates

- On app start, if the last check was over a day ago and there is a network, update yt-dlp (STABLE channel) in the background. On the very first launch do this before any YouTube probe if it finishes within a few seconds; otherwise proceed and let the error message suggest updating.
- If a download fails with the "out of date" classification, try one update and one retry automatically.

### 5.6 Sign-in

- A sign-in screen per site using a `WebView` on the site's own mobile login page. The app never sees or stores the password; it only reads the site's cookies after the user signs in.
- On "Done", read cookies with `CookieManager` for the site's domains and write a Netscape cookie file (desktop `cookies.ts` rules) to `filesDir/cookies/<site>.txt`. Pass `--cookies` only for URLs of that site (desktop `usesExtensionLogin`).
- Sign out deletes the file and the WebView cookies for those domains.
- The WebView keeps JavaScript on (needed for login) but has file access off, and only these sites' login pages are loaded in it.

### 5.7 Build, release, distribution

- ABI splits (arm64-v8a, armeabi-v7a, x86_64) plus a universal APK on GitHub Releases; per-ABI `versionCode` offsets so F-Droid can publish split APKs.
- Release signing key owned by the project owner, kept out of git (same pattern as the owner's TokBot project: values in `local.properties`, keystore git-ignored).
- GitHub Actions: on every push build + unit tests; on a `v*` tag build signed release APKs and attach them to a GitHub Release.
- F-Droid: `fastlane/metadata/android/en-US/` with title, short and full description, changelogs and screenshots; then a request-for-packaging / merge request to fdroiddata. No Google dependencies anywhere in the dependency tree.
- App icon from the desktop's `resources/icon.png`, made into an adaptive icon.

## 6. Testing

- JVM unit tests for all ported pure logic (format selector and quality options, error classification, URL helpers, ranges, filename sanitising, cookie file, update policy, queue rules), using the desktop's test cases as the starting point where they exist.
- Instrumented smoke test on a real device: probe and download a short public video as MP4 and MP3, pause/resume a plain file download, playlist with two entries.
- Manual checklist before each release: share flow from YouTube, Instagram and Facebook apps; screen off during a long download; phone restart mid-download; sign-in and sign-out.

## 7. Left to the builder

- Exact screen layouts, colours and typography within Material 3; the app icon treatment.
- Internal package structure, DI approach (manual is fine), and whether to use WorkManager anywhere alongside the service.
- How to detect "plain file vs media" in edge cases, as long as the common cases in 5.1 behave as described.
- Thumbnails loading library (must be FOSS, e.g. Coil).
- How cookies read from `CookieManager` (which gives only name=value pairs) are mapped to Netscape lines; writing them domain-wide (`.instagram.com TRUE / TRUE 0 name value`) is acceptable.
- Wording of messages, keeping them short and plain.

## 8. Risks

- **YouTube changes** break yt-dlp regularly; the daily auto-update is the mitigation. If YouTube starts requiring something QuickJS cannot do, the app may need a different JS runtime.
- **Library maintenance:** youtubedl-android's last release was November 2025. If it stalls, the fallback is `dev.ffmpegkit-maintained:yt-dlp-android` (Chaquopy, Python 3.13) or building Python ourselves.
- **APK size:** around 60 MB per ABI split, about 120 MB universal (measured: prototype debug APK with two ABIs was 123 MB).
- **OEM battery killers** (TECNO, Xiaomi, etc.) may stop the service anyway; show a one-time tip linking to the battery-optimisation setting if a download was killed.
- **F-Droid review** time and possible objections to the prebuilt native binaries inside youtubedl-android; Seal and YTDLnis are precedents that this is accepted.
