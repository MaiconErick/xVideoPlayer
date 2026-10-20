# xVideoPlayer

An offline-first video player for Android built on **mpv**. Local files, SMB shares and
remote servers in one library, with gamepad support, subtitle tooling, a private vault and a
built-in cutter.

## Features

- **mpv playback** — hardware decoding, subtitle rendering, per-video filters and picture
  adjustments.
- **Cloud and SMB** — browse remote servers and SMB shares, cache videos for offline use and
  keep a local copy list.
- **Private vault** — PIN-protected storage: videos moved into the vault disappear from the
  library and the gallery.
- **Cutter** — trim and export clips without re-encoding when the container allows it.
- **Collage** — build contact-sheet collages from a folder of videos.
- **Dual-screen mode** — video on the main display, controls/library on the second screen
  (AYN Thor).
- **Subtitles** — style editor, language selection and word lookup while watching.
- **Gamepad-first** — remappable buttons and tap zones for handhelds.
- **Screenshots** and metadata tagging.
- **Themes**, including a pixel-art Halloween theme.

## Tech

Kotlin · Jetpack Compose · Hilt · Room · Coil · Media3 · mpv (libmpv native libraries) ·
SMB via jcifs-ng · `minSdk 26`, `targetSdk 35`

## Architecture

```
app/src/main/java/com/app/xvideoplayer/
├── data/          Room database, repositories, preferences, private vault, file ops
├── player/        mpv wrapper, overlays, trimming, screenshot manager, SMB bridge
├── remote/        remote server clients and offline copies
├── ui/            home, library, player, cloud, collage, trim, vault, settings, themes
└── navigation/    Compose navigation
```

## Build

Requirements: JDK 17, Android SDK 36. Native mpv libraries are included under
`app/src/main/jniLibs/arm64-v8a`.

```bash
# local.properties
sdk.dir=/path/to/Android/Sdk

./gradlew assembleDebug
```

## License

MIT — see [LICENSE](LICENSE).
