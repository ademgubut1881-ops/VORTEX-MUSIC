# Vortex Music: Technical Document and Development Notes

Last updated: November 24, 2026 · Version: Beta 2 (in testing)
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
| AudioScreen.kt / AudioControls.kt | Audio screen (EQ graph, effect tiles, profiles, output device). Custom views at the end of the file: `EffectTileView` (fill tiles; text is drawn twice and clipped at the fill edge, white over the fill and normal color outside), `LockButtonView` (round padlock, purple when locked), `PureCardView` (Pure mode card: wavy line morphs into a straight line, sliding switch). Locking fades the content to 40% alpha (`LockLayout` also blocks touches). Profile options use `SheetDialog` |
| ChipButton.kt | `Ui.chipButton()`: small outlined Material 3 style chip, optional `SortGlyph` icon (three shrinking lines). Used for "Sort" and the cover buttons in list headers; the header row is right-aligned (thumb reach) |
| VortexRing.kt | Swirl animation around the cover (8 styles) and its settings |
| CoverGestures.kt | Now-playing gestures: swipe the cover to change song, pull the screen down to close |
| ColorUtil.kt | Finds the dominant color of album art |
| StartupAnim.kt | Startup animation |
| ArtistGroups.kt | Artist grouping, multi-artist splitting, alphabetical order |
| SmartSort.kt | Smart sorting (skips "The", sorts numbers correctly) |
| ListOptions.kt | List sorting and view options |
| Library.kt, Search.kt | Smart lists and search |
| Queue.kt | Queue screen: cover rows, swipe to remove, long-press drag with auto-scroll, slide-in/out animation, handle at the top to close |
| BottomSheet.kt | `SheetDialog`: Material 3 style bottom sheet (single choice, list, multi choice with a Save button). Used instead of `AlertDialog` for most choices |
| DialogStyle.kt, res/values/styles.xml | Rounded Material 3 style for the remaining `AlertDialog`s (`VortexDialog`, `VortexDialogLight`) |
| SettingsScreen.kt | Settings screen (grouped cards, tonal icon circles); tapping the title 7 times unlocks hidden developer tools |
| AudioTest.kt | `Headroom` (fixed -2 dB on Bluetooth since Nov 24; it was -5), `HeadroomProcessor`, and the hidden audio output test tool |
| PrivateLibrary.kt | Vortex folder: a user-chosen folder (Storage Access Framework tree) with a `.nomedia` file; scans it, caches metadata, copies songs in. Songs from it use negative ids and a `docUri`. Scan menu (`scanMenu`): manual "scan now" and an automatic-scan switch (`auto`, default on), reachable from quick settings and Settings |
| MiniPlayerBg.kt, NavLabels.kt, WaveBarsDrawable.kt | Mini player progress fill, bottom bar labels, wave-bar seek bar |
| StatsScreen.kt | Statistics screen |
| Backup.kt | Backup and restore |
| Duplicates.kt | Duplicate song finder |
| Lyrics.kt | Lyrics (currently disabled: `ENABLED = false`) |
| Pages.kt | Home, tools, "Downloads" placeholder (disabled) |
| Theme.kt, Ui.kt | Theme, colors, drawing helpers |
| Visuals.kt | `NeonBackgroundDrawable` (neon background: gradient, light blobs, glowing lines) and `IconDrawable` (20 icons drawn in code, no image files) |
| I18n.kt | Language support: `tx("tr", "en")` |
| Models.kt | `Song` data class: id, title, artist, album, added, duration, year, track, modified, file name, path. `uri` is built from MediaStore, or from `docUri` for songs in the Vortex folder |
| Tools.kt | Tool registry. `ToolEntry` (title, subtitle, icon, action) and `ToolHost` (activity, ui, song list, reload data, restart service). To add a tool, add one line each in `install()` and `entries()`. Currently: ThemeTool, BackupTool, DuplicatesTool, LyricsTool, AudioTestTool (hidden), PrivateLibrary |
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

After the engine, `HeadroomProcessor` lowers the level by a fixed **-2 dB when the output is Bluetooth** (0 dB on wired and speaker). It was -5 dB until Nov 24; a listener found Vortex too quiet and "flat" next to Poweramp and YouTube Music, so it was reduced. It is independent of Pure mode, so on Bluetooth even Pure mode is 2 dB lower than the file. It is a uniform gain change, not a filter. Whether any value improves clarity is not proven (see section 7).

**Pure mode** (`pure` in the audio prefs) bypasses the EQ, bass, width, loudness and auto leveling. **EQ lock** (`eqlock`) blocks touches on the audio screen sliders.

On skip or song change (`onFlush`) all filter and limiter state is cleared (prevents clicks).
Parameters are smoothed over 20 ms (prevents zipper noise).

The processor also measures data for animations: `level`, `beatCount` (bass hits) and `spectrum` (32 bands, 1024-point FFT). No microphone permission is needed.

## 5. Stored settings (SharedPreferences)
- `vortex_artist`: `sep` (artist separator bits), `smart` (smart sorting)
- `vortex_ring`: `style` (0-9; the picker shows 0 Off, 1 Ring, 9 Vortex, 2 Swirl, 5 Breath, 8 Drop; hidden: 3, 4, 6, 7 map to Ring), `color` (0 album color, 1 Vortex color, 2 rainbow)
- `vortex_audio`: `pure`, `eqlock`, `headroom_off` (test only), `devtools` (hidden tools unlocked)
- `vortex_private`: `tree` (chosen folder URI), `only` (show only the Vortex folder; with it on, All songs is empty), `auto` (automatic scan, default true), `cache` (scanned song metadata)
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
- Vortex folder: playback, covers and scans were reported working on Nov 22. Songs from it are kept out of All songs, Artists, Albums and the album/artist detail lists (`libSongs`), and shown in their own list (`libView = 12`); Liked, search and the queue still include them. Because their ids are negative, any code that treats `id < 0` as "no song" is wrong (the like button had this bug until Nov 24; use `byId.containsKey(id)`). Original note: written on Nov 21-22. Scans run on a background thread; a very large folder may take a while the first time. Copying songs in doubles the used space (originals stay). Android does not allow choosing `Download` or `Android/data` as the folder.
- Bluetooth headroom is a hypothesis: with the processor removed from the path, the reported "drum-stick detail smothered" effect remained, so the cause is still open. On Nov 23 the owner compared Vortex with Poweramp and YouTube Music on Bluetooth and found Vortex "flat" and quieter. A code review of `VortexEq.kt` found no obvious defect (double-precision biquads, look-ahead limiter, untouched pass-through when effects are off), so the first fix was lowering the headroom to -2 dB (Nov 24, not yet confirmed by listening). Still to do: a blind A/B at matched loudness (0 / -2 / -5 dB), Pure mode headroom of 0 dB, a gentler default "Vortex character" tuning, and checking the file format with the hidden audio output test tool.
- Library view numbers (`libView`) are shared by several screens: 0 library, 1 liked, 2 artists, 3 artist detail, 4 albums, 5 album detail, 6 folders, 7 folder detail, 8 smart lists, 9 smart detail, 10 all songs, 11 tools, 12 Vortex folder. Check the list before adding a new one (a clash on Nov 23 opened Settings instead of the Vortex folder).
- The light theme (Snow White) has low-contrast spots (wave-bar track, some icons).
- Headphone profiles: AutoEq data is not bundled because of ownership and license concerns.

## 8. Beta 3 ideas
Automatic backup (so settings survive a phone reset; Android Auto Backup rules or a periodic backup file) · Queue sheet from the player (pull up from the bottom) · splitting MainActivity.kt · per-song EQ memory · Vortex Recap (year-end summary, on device) · chapter marks and trim points · listening-health reminder · Vortex shuffle · song notes · parametric EQ · ReplayGain tags · crossfade · Room database.

### Beta 3 notes (no new features during the Beta 2 test period)
- Feedback from tester Behruz (Nov 16): (1) swipe up on the mini player to open the player screen (added to CoverGestures, the reverse of the close gesture); (2) a visible settings button (gear icon) for all settings. Item 2 is a discoverability issue and may go into the fix week.
- Bass hits too hard on headphones: consider automatically reducing the bass enhancer when a headphone correction is active (avoid stacked bass); waiting for test results.
- Full-width live spectrum on the player screen
- Pill-shaped round buttons on the player, cover scaled to screen size
- Real waveform seek bar (currently a decorative pattern; could be extracted by decoding the file, slow)
- Headphone wizard: first-run "How are your headphones?" question, correction applied automatically
- Clarity investigation: compare with Poweramp at matched volume with effects off; soften the harmonic bass and preamp if needed
- Automatic backup, queue sheet from the player, per-song EQ, Vortex Recap, parametric EQ, crossfade, Room, measured Bluetooth latency
- Tester feedback (Behruz, Nov 16): drag-up on the mini player opens the player, and a visible settings (gear) button on the home header: DONE (treated as a usability fix)
- Bass weight: headphone correction + bass enhancer + preset bass boosts stack; consider auto-reducing the bass enhancer while a correction is on (pending listening-test results)
- Bottom nav: room for a Tools tab (currently 3 tabs + a hidden 4th)

## 9. Development log
- Nov 6: Beta 1 published.
- Nov 8: English UI added (language option: Turkish / English / System).
- Nov 13: Own 10-band EQ and audio engine. Presets reorganized. Listening feedback: weak bass, clicks when boosting → smoothing and limiter added.
- Nov 15 (evening): Real cause of the start-of-song cut found: `Fader` (Queue.kt) set the volume to 0 on the "media item changed" event, but that event arrives after audio has already started, which chopped the intro. Fader no longer mutes; the soft start now lives in the audio engine (VortexEq, 12 ms sample-accurate ramp after flush). Bass protector state is also cleared on flush. EQ graph got a live spectrum and neon glow. Animation sync: visual values are read delayed by the output latency (speaker 110 ms, wired 90 ms, Bluetooth 280 ms base estimate, plus user fine-tuning). Note: Bluetooth latencies are estimates, not measured. Open issue: clarity on headphones is not yet on par with Poweramp (under investigation).
- Nov 15 (night): UI polish: labels under bottom-nav icons (NavLabels.kt), mini player card fills with playback progress (MiniPlayerBg.kt), wave-bar seek bar on the player (WaveBarsDrawable.kt, decorative pattern, not a real waveform), animation sync control with visible Auto/Manual/Off state. No new features until Beta 2 testing is done.
- Nov 16: Tester suggestions done: drag up on the mini player opens the player (CoverGestures.attachMini); a settings gear was added to the home header (IconDrawable.GEAR). Start-of-song cuts confirmed fixed by the user. Remaining: bass hits too hard on headphones (waiting for a settings test).
- Nov 14: Effect tile UI, auto leveling, artist search/grouping/photos, artist separator settings, smart sorting, swirl animation (8 styles), bass beat and spectrum.
- Nov 15: Feedback: bass hits too hard on headphones → bass protector added, preamp compensation raised from 50% to 75%, Instrumental and Deep Bass presets rebalanced. Auto leveling made gentler, state cleared on song change (prevents start-of-song click). Swipe the cover to change song (and play), pull the screen down to close. MainActivity.kt split (CoverGestures.kt, ColorUtil.kt).
- Nov 17-18: Settings screen in grouped cards; Queue redesign (cover rows, swipe to remove, long-press drag with auto-scroll).
- Nov 19-20: Pure mode, EQ lock, audio output test tool (hidden later). Tests showed -9 dB headroom clearer but too quiet; -6 acceptable; final choice -5 dB on Bluetooth only.
- Nov 21: Material 3 style (no library): tonal surfaces, medium type weight, bottom sheets (`SheetDialog`), rounded dialogs, new bottom bar, queue opens from a handle at the bottom of the player. Rings: Drop (new), spiral Swirl, rainbow color; Pulse, Orbit, Rhythm and Spectrum hidden. Themes reduced to Sage / Night Black / Snow White first. `skipNext()` loads all songs when the queue has one song. Pasting `MainActivity.kt` over ~100k characters truncated the end (build error `REQ_ALBUM_ART`); the file was shrunk to ~80k by removing comments, blank lines and extra indentation.
- Nov 22: Quick settings sheet from the gear button on the player. Multi-choice bottom sheet (artist separators). Equalizer screen restyled (tonal cards, pill chips, rounded effect tiles, plain graph line without neon glow). Vortex folder (`PrivateLibrary.kt`). Smooth slide animation for the queue and a handle to close it. New startup animation (letters pulled into the V).
- Nov 22 (later): Player screen layout rebuilt in `activity_main.xml`: `npTitleRow` (title, artist, like heart), controls row with shuffle and repeat cells around prev / play / next. The Folder and Speed cells are kept but hidden (`visibility=gone`) so the existing click code still binds; their actions moved to the quick settings sheet (`speedSheet()`, add to folder). Quick settings reduced to six items. Only the background gradient follows the cover accent now; the play button (`neonGradient(false, 40)`), seek wave and thumb stay Vortex purple for contrast and identity. The like heart in song rows is clickable (`toggleFavoriteId`).

- Nov 22 (evening): Library scan options (manual and automatic) added to quick settings and Settings.
- Nov 23-24: Vortex folder moved to its own row (`libView = 12`) and kept out of All songs, Artists and Albums. Effect tile text readability fixed. Profile menu moved to a bottom sheet. New lock button (fades the audio screen) and Pure mode card. Small outlined "Sort" chip on the right (`ChipButton.kt`). Fixed: like heart did nothing for Vortex folder songs (negative ids); top icons of the player now always Vortex purple. Sound comparison with Poweramp and YouTube Music: Vortex felt flat and quiet, so the Bluetooth headroom went from -5 to -2 dB. README now states that the sound engine is still in development and asks for feedback.

## 10. Working rules (notes to self)
- When changing a file, delete the old one completely and paste the new one (appending causes "Redeclaration" errors).
- Upload the code to GitHub and take a backup before adding a new feature.
- No big features in the last week before release, only fixes.
- Do not change audio settings without listener feedback.
- Update this document after every big change.
- Before adding a new screen/view number, search the existing `libView` values.
- Never use `id < 0` to mean "no song"; songs from the Vortex folder have negative ids.
- Code comments are still partly in Turkish; they will be translated to English before release.
