# Raftaaro for Android

A free, open-source download manager for Android, with no ads and no tracking. It is the Android version of [Raftaaro](https://github.com/Hamza-Bhai/Raftaaro-Downloader) for Windows.

See a video in YouTube, Instagram, Facebook or any other app, tap **Share → Raftaaro**, pick a quality, done. The download runs in the background and the file appears in your gallery.

## Features

- **Videos and audio** from YouTube, Instagram, Facebook, TikTok, X, Vimeo and the many other sites [yt-dlp](https://github.com/yt-dlp/yt-dlp) supports
- Only the qualities the video really has (144p up to 4K/8K), each with an estimated size; audio as MP3 or M4A
- **Playlists and channels**: tick the videos you want; they go in a folder named after the playlist
- **Any file link** (zip, apk, pdf…) over up to 16 connections at once
- **Queue** with a limit on parallel downloads; YouTube downloads run one at a time and back off when YouTube asks
- **Pause and resume**, even after the phone restarts
- **Sign in** to YouTube, Instagram or Facebook inside the app for videos that need it; your sign-in stays on your phone
- Offers to download a link you copied
- yt-dlp updates itself once a day

Files are saved to `Download/Raftaaro/Videos`, `/Audio` and `/Files` (changeable in Settings). Android 8.0 or newer.

## Install

Download the APK for your phone from [Releases](https://github.com/Hamza-Bhai/Raftaaro-Android/releases/latest): `arm64-v8a` for almost all phones from the last years, `armeabi-v7a` for older ones, `universal` if unsure. Raftaaro is also being submitted to F-Droid.

Raftaaro is not on Google Play: Play does not allow YouTube downloaders.

## Build from source

Requirements: JDK 17 and the Android SDK (platform 35). Put the SDK path in `local.properties`:

```
sdk.dir=C\:/path/to/android/sdk
```

| Command | What it does |
| --- | --- |
| `./gradlew testDebugUnitTest` | Unit tests (JVM) |
| `./gradlew connectedDebugAndroidTest` | Device tests (a phone with USB/wireless debugging; some need internet) |
| `./gradlew assembleDebug` | Debug APKs in `app/build/outputs/apk/debug/` |
| `./gradlew assembleRelease` | Release APKs (R8-shrunk), one per ABI plus a universal one |

### Release signing

Create a key once and keep it safe — without it you cannot ship updates:

```
keytool -genkeypair -v -keystore keystore/raftaaro-release.jks -alias raftaaro -keyalg RSA -keysize 4096 -validity 10000
```

Then add to `local.properties` (git-ignored):

```
release.storeFile=keystore/raftaaro-release.jks
release.storePassword=...
release.keyAlias=raftaaro
release.keyPassword=...
```

Without these, a release build is left unsigned (F-Droid signs its builds with its own key). For a quick local test, install the debug build instead.

For the GitHub release workflow (`.github/workflows/release.yml`, runs on `v*` tags), add the repository secrets `RELEASE_KEYSTORE_BASE64` (the `.jks` file in base64), `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD`.

## Privacy

Everything stays on your phone. There are no analytics, ads or accounts. Sign-in cookies are kept in the app's private storage and only passed to yt-dlp for that site's links.

## Responsible use

Download only content you have the right to download. Respect copyright and the terms of service of the sites you use.

## Credits

[yt-dlp](https://github.com/yt-dlp/yt-dlp) (Unlicense), [FFmpeg](https://ffmpeg.org/) (LGPL 2.1+), Python (PSF) and QuickJS (MIT), bundled by [youtubedl-android](https://github.com/yausername/youtubedl-android) (GPL-3.0).

## License

[GPL-3.0](LICENSE), because the bundled youtubedl-android library is GPL-3.0. The [desktop app](https://github.com/Hamza-Bhai/Raftaaro-Downloader) is MIT.
