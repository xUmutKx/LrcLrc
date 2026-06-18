# LrcLrc — Lyric Finder for Android

Search your local `.lrc` lyric files instantly and jump to the exact position in Poweramp.

## Architecture

```
app/src/main/java/com/lrcfinder/app/
├── LRCFinderApp.java       Application — Material You dynamic colors
├── BaseActivity.java       Theme (light/dark/AMOLED) applied before setContentView
├── MainActivity.java       Main screen: drawer, search, browse list, loading overlay, What's New
├── SettingsActivity.java   Settings: SAF folder picker, theme toggle, GitHub link
├── LyricsRepository.java   Singleton: streaming background index + concurrent search
├── AlbumArtLoader.java     MediaStore album art by audio path, memory-cached
├── ResultsAdapter.java     Search results with highlighted snippets + play button
├── BrowseAdapter.java      Pre-search library list with album art
├── PowerampHelper.java     Poweramp API: cmd=20 (OPEN_TO_PLAY), file:// URI, ms seek
└── Prefs.java              SharedPreferences wrapper + version tracking + GITHUB_URL
```

## Key Features

| Feature | Implementation |
|---|---|
| Streaming index | `reindex()` adds to `liveIndex` incrementally; search snapshots at call time |
| Concurrent search during index | Separate `searchExecutor` pool; never waits for index to finish |
| Browse list updates live | `browseRefresher` Runnable fires every 1 s while indexing |
| Turkish CI search | `trLower()`: `I→ı`, `İ→i` before `toLowerCase(Locale.ROOT)` |
| Poweramp play | `ACTION_API_COMMAND` broadcast, `cmd=20`, `data=file://`, `pos=ms` |
| Album art | `MediaStore.Audio.Media.ALBUM_ID` → `content://media/external/audio/albumart/{id}` |
| Adaptive icon | `mipmap-anydpi-v26/ic_launcher.xml` with foreground + background layers |
| AMOLED theme | `Theme.LRCFinder.Amoled` in `values-night/themes.xml` overrides surface to `#000` |
| Instant theme apply | `recreate()` called on theme/AMOLED change in SettingsActivity |
| SAF folder picker | `OpenDocumentTree` → convert `primary:path` to `/sdcard/path` |
| What's New dialog | `Prefs.getLastSeenVersion()` compared to `CURRENT_VERSION` on every launch |

## Build

```bash
# GitHub Actions (recommended — no local SDK needed)
git add -A && git commit -m "msg" && git push
gh run watch
gh run download --name app-debug --dir /sdcard/Download/

# Local (Android Studio or Gradle)
./gradlew assembleDebug
# APK → app/build/outputs/apk/debug/app-debug.apk
```

## Adding a New Version

1. Bump `versionCode` / `versionName` in `app/build.gradle`
2. Update `CURRENT_VERSION` in `MainActivity.java`
3. Update the `notes` string in `maybeShowWhatsNew()`
4. Commit and push — GitHub Actions builds the APK

## Known Limitations

- SAF path conversion only handles primary storage (`primary:…`). SD card paths fall back to `/sdcard`.
- Album art cache has no size limit — consider LRU eviction for very large libraries.
- Poweramp `pos` seek tested on Poweramp v3; may vary on v2.
- No landscape-specific layout; works but isn't optimised.

## Developer

**xUmutKx** — https://github.com/xUmutKx/LrcLrc
