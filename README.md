# Raftaaro-Android

Android version of [Raftaaro](https://github.com/Hamza-Bhai/Raftaaro-Downloader), the free, open-source download manager. **This is an early prototype.**

Built with Kotlin and Jetpack Compose. Video and audio extraction is done by [yt-dlp](https://github.com/yt-dlp/yt-dlp) through [youtubedl-android](https://github.com/yausername/youtubedl-android), which bundles Python, ffmpeg and QuickJS.

## What the prototype does

- Paste a link, or share it from YouTube, Instagram, a browser… to Raftaaro
- **Info**: title, uploader, length and available qualities
- **Video**: best quality up to 1080p, saved as MP4
- **MP3**: audio only
- **Engine check**: shows the bundled yt-dlp and Python versions
- **Update yt-dlp**: updates yt-dlp from inside the app

Files are saved to `Download/Raftaaro`. Android 10 or newer is required.

Not yet: background downloads, queue, playlists, logins, multi-part file downloads.

## Build

Requirements: JDK 17 and the Android SDK (platform 35). Put the SDK path in `local.properties`:

```
sdk.dir=C\:/path/to/android/sdk
```

Then:

```bash
./gradlew assembleDebug    # APK in app/build/outputs/apk/debug/
```

## Responsible use

Download only content you have the right to download. Respect copyright and the terms of service of the sites you use.

## License

MIT
