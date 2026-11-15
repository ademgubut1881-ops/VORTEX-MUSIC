# Vortex Music: Technical Document and Development Notes

Last updated: November 15, 2026 · Version: Beta 2 (in testing)
Package: `io.github.vortexmusic.player` · License: GPL-3.0 · Brand: Quiet Apps

## 1. Overview
A free, offline (no INTERNET permission), ad-free, tracker-free, open-source Android music player.
It plays local music files and has its own audio processing engine ("Vortex Audio Engine").
UI: Kotlin + Android Views (no Compose). Developed entirely on a phone with CodeAssist.

## 2. Technology
- Language: Kotlin · UI: Views (built in code)
- Playback: Media3 / ExoPlayer, `MediaSessionService` (notification, lock screen, Bluetooth controls)
- Audio processing: Media3 `BaseAudioProcessor` (our own DSP)
- Data: songs are read from `MediaStore`; settings and statistics use `SharedPreferences`
- No database (Room) yet. Considered for Beta 3 (notes, markers, per-song EQ).
- Formats: Android's own decoders (MP3, AAC, FLAC, OGG, Opus, WAV, M4A). Varies by device.

## 3. Files and responsibilities
| File | Purpose |
|---|---|
| MainActivity.kt | Main screen, tabs, library lists, now-playing screen, mini player |
| Engine.kt | `PlaybackService`, `AudioFxController` (effects, presets, persistence) |
| VortexEq.kt | Audio engine: EQ, bass, stereo width, loudness, limiter, auto leveling, level meter, bass-beat detector, spectrum |
| AudioScreen.kt / AudioControls.kt | Audio screen (EQ graph, effect tiles, profiles, output device) |
| VortexRing.kt | Swirl animation around the cover (8 styles) and its settings |
| CoverGestures.kt | Now-playing gestures: swipe the cover to change song, pull the screen down to close |
| ColorUtil.kt | Finds the dominant color of album art |
| StartupAnim.kt | Startup animation |
| ArtistGroups.kt | Artist grouping, multi-artist splitting, alphabetical order |
| SmartSort.kt | Smart sorting (skips "The", sorts numbers correctly) |
| ListOptions.kt | List sorting and view options |
| Library.kt, Search.kt | Smart lists and search |
| Queue.kt | Queue screen |
| StatsScreen.kt | Statistics screen |
| Backup.kt | Backup and restore |
| Duplicates.kt | Duplicate song finder |
| Lyrics.kt | Lyrics (currently disabled: `ENABLED = false`) |
| Pages.kt | Home, tools, "Downloads" placeholder (disabled) |
| Theme.kt, Ui.kt | Theme, colors, drawing helpers |
| Visuals.kt | `NeonBackgroundDrawable` (neon background: gradient, light blobs, glowing lines) and `IconDrawable` (20 icons drawn in code, no image files) |
| I18n.kt | Language support: `tx("tr", "en")` |
| Models.kt | `Song` data class: id, title, artist, album, added, duration, year, track, modified, file name, path. `uri` is built from MediaStore |
| Tools.kt | Tool registry. `ToolEntry` (title, subtitle, icon, action) and `ToolHost` (activity, ui, song list, reload data, restart service). To add a tool, add one line each in `install()` and `entries()`. Currently: ThemeTool, BackupTool, DuplicatesTool, LyricsTool |
| AlbumArt.kt | User-added album/artist covers. Saved as JPEG (max ~900 px) in the app's private folder (`filesDir/albumart`); keys stored in `vortex_art` prefs. LruCache + single worker thread. `keyFor()` picks the album cover first, or the artist cover if "by artist" is on |
| Covers.kt | Song cover loading: cache first, then in the background (2 threads) Android 10+ `loadThumbnail`, falling back to the embedded picture (`MediaMetadataRetriever`). Failures are remembered in a `missing` set and not retried |

## 4. Audio signal flow (the most important part)
Song file → ExoPlayer (decoding) → **VortexEqProcessor** → audio output

Inside VortexEqProcessor, in order:
1. 10-band EQ (RBJ peaking biquad, Q = 1.41) at 31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz
2. Harmonic bass enhancer
3. Stereo width (mid/side)
4. Bass protector: watches only the band below 140 Hz and, if it gets too loud (threshold 0.40), turns that band down without touching the highs (for harsh bass hits on headphones)
5. Gain: preamp (compensates 75% of the EQ's peak boost) + loudness boost + automatic level (slow: target -18 dBFS, cuts at most 6 dB, boosts at most 2 dB; 1.5 s when cutting, 4 s when boosting)
6. Look-ahead limiter (3 ms look-ahead, ceiling 0.95) to prevent clipping

On skip or song change (`onFlush`) all filter and limiter state is cleared (prevents clicks).
Parameters are smoothed over 20 ms (prevents zipper noise).

The processor also measures data for animations: `level`, `beatCount` (bass hits) and `spectrum` (32 bands, 1024-point FFT). No microphone permission is needed.

## 5. Stored settings (SharedPreferences)
- `vortex_artist`: `sep` (artist separator bits), `smart` (smart sorting)
- `vortex_ring`: `style` (animation style 0-7), `color` (0 album color, 1 Vortex color)
- Audio effects: `levels10` (10 bands), `enabled`, `autolevel`, profiles
- `vortex_stats`: play counts and last-played times
- `vortex_art`: custom cover keys

## 6. Versioning and release
- Beta 1: published (Nov 6)
- Beta 2: 3-week self-test from Nov 14, then ~1 week of fixes only, then release
- Beta 3: new features. Then the final release.
- `versionCode` must increase with every release (otherwise the update will not install)
- The signing keystore and its password must NEVER be put on GitHub

## 7. Known risks and to-do
- `loadSongs()` runs on the main thread: short freeze possible with a big library. To be moved to a background thread.
- On Bluetooth headphones, animations may run slightly ahead of the sound (latency is not measured).
- **MainActivity.kt gets truncated when pasted into CodeAssist above ~100,000 characters.** Keep it under 95,000 and split it further (Beta 3).
- The `onFlush()` signature may differ between Media3 versions (fix if it fails to compile).
- Covers.kt: covers that were not found are not retried during a session. Restart the app after adding a cover.
- Headphone profiles: AutoEq data is not bundled because of ownership and license concerns.

## 8. Beta 3 ideas
Queue sheet from the player (pull up from the bottom) · splitting MainActivity.kt · per-song EQ memory · Vortex Recap (year-end summary, on device) · chapter marks and trim points · listening-health reminder · Vortex shuffle · song notes · parametric EQ · ReplayGain tags · crossfade · Room database.

## 9. Development log
- Nov 6: Beta 1 published.
- Nov 8: English UI added (language option: Turkish / English / System).
- Nov 13: Own 10-band EQ and audio engine. Presets reorganized. Listening feedback: weak bass, clicks when boosting → smoothing and limiter added.
- Nov 14: Effect tile UI, auto leveling, artist search/grouping/photos, artist separator settings, smart sorting, swirl animation (8 styles), bass beat and spectrum.
- Nov 15: Feedback: bass hits too hard on headphones → bass protector added, preamp compensation raised from 50% to 75%, Instrumental and Deep Bass presets rebalanced. Auto leveling made gentler, state cleared on song change (prevents start-of-song click). Swipe the cover to change song (and play), pull the screen down to close. MainActivity.kt split (CoverGestures.kt, ColorUtil.kt).

## 10. Working rules (notes to self)
- When changing a file, delete the old one completely and paste the new one (appending causes "Redeclaration" errors).
- Upload the code to GitHub and take a backup before adding a new feature.
- No big features in the last week before release, only fixes.
- Do not change audio settings without listener feedback.
- Update this document after every big change.
- Code comments are still partly in Turkish; they will be translated to English before release.
