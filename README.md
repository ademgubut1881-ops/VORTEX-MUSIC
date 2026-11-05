# Vortex Music

A free and open-source music player for Android that plays the music already on your phone.
No account, no ads, no tracking, and no internet permission.

> **Status: beta (1.0.0-beta.1).** The interface is currently in Turkish only. An English translation is planned.

## Why Vortex

- **Private by design.** Everything stays on your phone. The app does not request the INTERNET permission at all.
- **Made for people who care about their library.** Sort, group and arrange your songs the way you like, and the order only lives inside the app. Your files are never touched.
- **Sound you can shape.** An equalizer you adjust by dragging points on a curve, profiles that switch automatically when your headphones connect, and more.

## Features

**Library**
- Songs, artists, albums, folders, favorites and smart lists (most played, recently added, never played, forgotten songs)
- Add your own cover image to any album
- Group all songs of an artist into one album, or use the album names stored in your files
- 14 sort options, list and grid layouts, "titles only" mode, and manual ordering with up/down controls (per list, stored only in the app)
- Duplicate file finder with safe deletion (Android 11 and later)

**Playback**
- Background playback with notification controls (tapping the notification opens the app)
- Queue screen: reorder, remove, "play next", "add to queue"
- Shuffle, repeat, speed control (0.75x to 1.5x) with an optional pitch-linked "vinyl" mode
- Smooth fade-in, and the app remembers your last queue and position
- The now-playing screen takes its colors from the current cover art

**Sound**
- Equalizer with a draggable curve, presets, bass boost, loudness and virtual surround
- Save your settings as profiles, export and import them as files
- Automatic profile switching when a Bluetooth or wired headphone connects

**Search**
- Search that ignores case and diacritics (typing "sezen" finds "Şezen")
- Search by song, artist or album, with sorting and recent searches

**Your listening**
- Statistics: total time, daily curve, artist share, hours of the day, top songs (kept only on your phone)

**Lyrics**
- Import `.lrc` or `.txt` files, or paste lyrics. Synced lyrics highlight line by line as the song plays and you can tap a line to jump to it

**Appearance**
- System, light or dark mode
- Color schemes (Vortex, Midnight / First Snow, and several more), a dynamic scheme on Android 12 and later, optional blurred color glow and neon lines

**Backup**
- Backup and restore of your favorites, folders, lyrics, covers, orders and settings (made for restoring on the same phone)

## Install

1. Download the latest APK from the [Releases](https://github.com/<kullanici-adin>/<depo-adi>/releases) page.
2. Allow installing from your browser or file manager when Android asks.
3. Open Vortex and grant the music permission.

**Requirements:** Android 8.0 (API 26) or later. Duplicate deletion needs Android 11+, dynamic colors need Android 12+.

**Updating:** Releases are all signed with the same key, so a new APK installs over the old one and keeps your data. To get notified automatically, add this repository to [Obtainium](https://github.com/ImranR98/Obtainium): open Obtainium, choose "Add app", paste the repository address and Vortex will be tracked from the Releases page.

## Privacy

Vortex does not collect, send or share any data. It has no internet access, no analytics and no ads.

Permissions it asks for, and why:

| Permission | Why |
|---|---|
| Music and audio (`READ_MEDIA_AUDIO`, or `READ_EXTERNAL_STORAGE` on Android 12 and below) | To list and play your songs |
| Notifications | To show the playback controls |
| Foreground service (media playback), wake lock | To keep playing when the screen is off |
| Bluetooth connect | To read the name of your headphones, so a saved sound profile can switch automatically |

Backup files are created only when you ask for them, and you choose where they are saved.

## Build from source

Vortex was written entirely on a phone with the [CodeAssist](https://github.com/tyron12233/CodeAssist) IDE.

- Language: Kotlin, classic Android Views (no Compose)
- Player: AndroidX Media3 (ExoPlayer and MediaSession)
- Package: `io.github.vortexmusic.player`, minSdk 26, targetSdk 36

You can import the project folder into CodeAssist. CodeAssist can also export a project as a Gradle build. That path has not been tested with this project yet.

**About signing:** Official releases are signed with the maintainer's private key, which is not part of this repository. If you build the app yourself, sign it with your own key. An APK signed with a different key cannot be installed over an official release and the other way round.

## Known issues

- The interface is Turkish only for now.
- The equalizer uses the effects built into Android. The strength of the bass, loudness and surround effects depends on your phone brand and headphones, and the equalizer has the bands (usually 5) and fixed frequencies your phone provides.
- Editing song tags (title, artist, album) is not available yet.
- Backups are meant to be restored on the same phone, because they refer to songs by the numbers Android assigns to them.

## Roadmap

- English translation
- An About screen and a "What's new" screen after updates
- A glass-style look
- Tag editing

Plans can change. Ideas and bug reports are welcome, please open an issue.

## Contributing

Open an issue to report a bug or suggest a feature. For code changes, open a pull request and describe what it does and how you tested it.

## License and credits

Vortex Music is licensed under the **GNU General Public License v3.0** (see `LICENSE`).

It uses [AndroidX Media3](https://developer.android.com/media/media3), which is licensed under the Apache License 2.0.

The app logo was generated with the help of AI.
