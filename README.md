# PixelGo

A pixel-art studio for Android, built with Kotlin and Jetpack Compose. You can **draw**, **animate**,
**record or import sound**, **edit it all on a timeline**, and **export video**, all in one app.

PixelGo is **local-only**: no accounts, no cloud, no network access (the app does not request the
`INTERNET` permission). Projects and palettes live in the app's private storage on the device.
Exports go to the phone's `Pictures/PixelGo` folder (images, GIFs) or `Movies/PixelGo` (videos).

---

## Features

| Area | What it does |
|---|---|
| **Canvas** | Any size from 1×1 to 256×256. Pinch to zoom, two-finger pan, pixel grid toggle, optional vertical/horizontal center guide lines, "Fit to screen". |
| **Drawing** | Pencil, eraser, flood fill, eyedropper. One pixel = one color (no brushes, by design). Strokes are interpolated so fast swipes leave no gaps. |
| **Symmetry** | Mirror left↔right and/or top↕bottom (applies to pencil, eraser and fill). Mirror axes are shown as dashed pink lines. |
| **Transparency** | Full alpha everywhere. A checkerboard shows transparent areas. Colors can be partly transparent (alpha slider / `#AARRGGBB` hex). |
| **Layers** | Add, duplicate, delete, rename, show/hide, reorder (drag the ≡ handle or use the arrows), merge down (asks for confirmation). Layer operations apply to every frame. |
| **Select** | Box or paint-brush selection. Copy, cut, move, paste, delete, select all/none. Moved/pasted cells float above the canvas: drag them, then ✓ to drop or ✕ to cancel. The clipboard works across projects until the app closes. |
| **Animation** | **No frame limit:** frames are stored on the phone's storage, not in memory. Frames share the same layers. Speed is 1–60 fps, and each frame can be held for 1–999 ticks. Onion skin shows the previous frame in red and the next in blue. Playback is driven by the sound clock, so picture and sound stay in sync. |
| **Timeline** | **Compact strip**, or **tap ▲ for the full editor**, which has: a zoomable time ruler (pinch to zoom, flick to scroll, drag the ruler to scrub), frames drawn as wide as they are held, and thumbnails. **Long-press a frame** to insert blank before/after, duplicate after/to end, copy/paste, set the hold, move or delete. **Range mode** lets you select a run of frames and copy, cut, paste (before/after/at end), duplicate, reverse, set the hold, move, or delete them. **Frames can be copied between projects** (missing layers are added, and other canvas sizes are centered). A setting makes **duplicate always add to the end**. |
| **Sound** | **Tracks of clips** (music, voice, effects…). **Import** any sound the phone can decode (MP3, AAC/M4A, WAV, OGG, FLAC, Opus, or the audio of a video). **Record** with the microphone, either as a **voice-over** (the animation plays while you record) or **"record here"** at the current frame. The 3-2-1 count-in plays three ticks that the microphone hears, which lines the recording up with the picture to within a millisecond. Clips show their waveform. **Tap** a clip for volume (up to 400%, or **Normalize**), fades, nudge, split, effects, noise cleanup, duplicate or delete. **Long-press and drag** to move it (it snaps to frames and can go to another track); drag its edges to trim. Tracks can be muted, renamed, set in volume, or deleted. **"Loop animation until the sound ends"** repeats a short animation under a long song. |
| **Sound editing** | **Sound mode** (the waveform button in the full timeline) gives the sound tracks the whole screen, with the current frame shown small at the top. **Zoom** in time down to single samples. **Pinch up/down** for taller tracks and pick a **waveform size** (×1–×16) so quiet sound is easy to see. With the **Select** tool, drag across one or more tracks to pick any section, then: **cut, copy, paste, delete** (leaves a gap), **close gap**, **split**, **duplicate**, **insert silence**, **fade in/out**, or set its **volume**. **Copy and paste work between projects.** **Fades:** drag the handles at a clip's top corners. **Volume line:** with the Volume tool, tap to add a point, drag to move it, long-press to remove it. Clips that overlap on a track **crossfade** automatically. **Effects:** reverse, speed, pitch, tape-style speed, low cut, bass/mid/treble, and a noise gate. **Clean up noise (AI)** removes background noise with a neural network that runs on the phone (RNNoise), with a strength slider and **Before/After** buttons to compare. **Re-record a section** (punch-in) plays 2 s before the section, records, stops on its own, and puts the new take exactly where the old one was. Every edit is one undo step. |
| **Palettes** | Built-in "Default" palette plus unlimited custom palettes: create, rename, duplicate, delete, add/edit/reorder/remove colors. Color picker with HSV square, hue bar, alpha bar, hex entry and recent colors. |
| **Undo / redo** | Every edit (pixels, layers, frames, holds, ranges, pastes, sound clips and tracks) is undoable, up to 100 steps per editing session. |
| **Saving** | Autosaves 2 s after the last edit, when leaving the editor, and when the app goes to the background. Only what changed is written, so long animations save instantly. |
| **Export** | **Images:** transparent PNG at 1×–16× (nearest-neighbour, stays crisp), each layer, animated GIF, sprite sheet, each frame. **Video:** **MP4** or **MOV** (H.264 + AAC sound) at 480p / 720p / 1080p / 4K, with the canvas shape or 16:9 / 9:16 / 1:1 and a background color of your choice. Pixels are scaled by even whole numbers so edges stay sharp. Long exports run in the background with a progress notification and a Cancel button. |

---

## Getting started

### Requirements

- **Android Studio** recent enough for **Android Gradle Plugin 9.4** (or just the command line with **JDK 17+**; development used Temurin JDK 21).
- **Android SDK Platform 37** (the project compiles against SDK 37 and targets 36). Android Studio's SDK Manager will offer to install anything missing.
- A device or emulator running **Android 8.0 (API 26) or newer**. Video export and recording need a real device with a hardware H.264 encoder and a microphone.

Gradle itself is downloaded automatically by the wrapper (Gradle 9.7.1).

### Build and run

Android Studio: **File → Open** the project folder, let Gradle sync, then press **Run**.
Android Studio creates `local.properties` (your SDK path) for you. It is intentionally not in git.

Command line: either set `ANDROID_HOME` to your SDK folder, or create `local.properties` containing
`sdk.dir=/path/to/Android/Sdk`. Then:

```bash
./gradlew assembleDebug        # build app/build/outputs/apk/debug/app-debug.apk
./gradlew installDebug         # build and install on the connected device
./gradlew testDebugUnitTest    # run the unit tests (JVM, no device needed)

adb shell am start -n com.pixelgo/.MainActivity   # launch after installing
adb logcat -b crash                               # check for crashes
```

On Windows use `gradlew.bat` instead of `./gradlew`. `ffprobe`/`ffmpeg` are handy for checking
exported videos (`ffprobe -show_streams video.mp4`).

**On-device media tests** (`app/src/androidTest`) check sound import, video export with sound and
the playback clock on a real phone. They don't touch the screen or your projects. **Don't use
`connectedAndroidTest`**: it uninstalls the app when it finishes, which deletes every project on
the phone. Install and run them by hand instead:

```bash
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.pixelgo.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s PixelGoTest     # the measured timings
adb uninstall com.pixelgo.test   # removes the test package only
```

There is **no release signing configuration yet**. Only debug builds are set up.

---

## Project layout

```
app/src/main/java/com/pixelgo/
├── MainActivity.kt            Navigation: gallery → editor → palettes
├── PixelGoApp.kt              Application class: repositories, settings, frame clipboard
├── model/
│   ├── Model.kt               PixelProject (layers, frames, audio, timing), Layer, Frame, Palette, limits
│   └── Audio.kt               SoundTrack, AudioClip (times in ms), AudioAsset, 48 kHz constants
├── editor/                    Pure Kotlin (no Android imports), fully unit-testable
│   ├── EditorEngine.kt        Every edit goes through here (pixels, selection, layers, frames, ranges, sound)
│   ├── History.kt             Undo/redo stack and all reversible Edit types
│   ├── CelStore.kt            Where pixels live: CelStore interface, CelKey, MemoryCelStore (tests)
│   ├── Timing.kt              Ticks ↔ frames ↔ ms (prefix sums + binary search)
│   ├── AnimationSnapshot.kt   Immutable copy of what's needed to render (export / playback)
│   ├── FrameClip.kt           FrameClipSource (frames on the cross-project clipboard)
│   ├── AudioMixer.kt          Mixes clips/tracks into 48 kHz stereo (preview + export); PcmSource
│   ├── ClipGain.kt            A clip's loudness at each moment: volume, fades, volume line, crossfades
│   ├── Resampler.kt           Windowed-sinc sample-rate converter (any rate → 48 kHz)
│   ├── CountIn.kt             The 3-2-1 count-in ticks, and finding them in a recording (lines recordings up)
│   ├── Names.kt               Default names that don't repeat (Voice 2, Recording 4…)
│   ├── audiofx/Effects.kt     Effect chain: resample, EQ (biquads), noise gate, time stretch (WSOLA)
│   ├── denoise/RnNoise.kt     AI noise removal: Kotlin port of RNNoise (FFT, pitch, GRU network)
│   ├── Selection.kt / Raster.kt / ColorMath.kt
├── data/
│   ├── DiskCelStore.kt        One compressed file per cel, LRU cache, dirty tracking, garbage collection
│   ├── MetaSerializer.kt      project.meta (format v3, tagged sections)
│   ├── Serializers.kt         Old .pxg reader (v1/v2, for migration) + palette file format
│   ├── Migration.kt           .pxg → folder conversion with byte-for-byte verification
│   ├── ProjectRepository.kt   Project folders: list/open/save/rename/duplicate/delete, thumbnails, sound files
│   ├── FrameClipboard.kt      Frame clipboard on disk (works across projects)
│   ├── PaletteRepository.kt / AppSettings.kt
│   ├── PngExporter.kt / GifEncoder.kt   Image exports (streamed frame by frame)
│   ├── audio/                 AudioDecoder (import), AudioRecorder (mic), PcmFiles (format + peaks), AudioSources,
│   │                          SoundRender (effects → new sound file), SoundClipboard (copy between projects)
│   └── video/                 VideoExporter (H.264+AAC, MediaCodec/MediaMuxer), AacPriming (sound sync),
│                              VideoLayout, YuvFrame, MovPatcher (MP4 → MOV), VideoOutput (Movies/PixelGo)
├── export/                    ExportJobs (one background export at a time) + ExportService (foreground service)
└── ui/
    ├── gallery/               Project grid, new-project dialog
    ├── editor/                EditorScreen, PixelCanvas, EditorViewModel, TimelineView (+dialogs),
    │                          AudioSheets (record / clip options), PreviewPlayer (sound + playback clock),
    │                          SoundModeBars + EffectsSheet (sound mode), WaveformCache (zoomed-in waveforms),
    │                          LayersSheet, SelectionBar, PaletteStrip, ExportDialog
    ├── palettes/ common/ theme/
app/src/main/assets/           rnnoise.bin (the noise-removal network's weights) + RNNOISE-LICENSE.txt
app/src/test/java/com/pixelgo/ EngineTest, AnimationTest, StorageTest, RangeTest, VideoTest, AudioTest,
                               SoundEditTest, EffectsTest, RnNoiseTest (113 tests) + reference sounds in resources/
app/src/androidTest/           MediaDeviceTest (12 on-device tests) + test sounds in assets/
tools/                         rnnoise_weights.py (makes rnnoise.bin), rnnoise_reference.py (RnNoiseTest's reference)
docs/ARCHITECTURE.md           How it fits together, file formats, gotchas. Read this before changing code.
```

The only third-party libraries are AndroidX / Jetpack Compose (see `gradle/libs.versions.toml`).
The noise removal is a Kotlin port of [RNNoise](https://github.com/xiph/rnnoise) v0.1.1 (Jean-Marc Valin,
BSD-3-Clause; the notice is in `app/src/main/assets/RNNOISE-LICENSE.txt`), using its original weights.
Video and audio use Android's built-in `MediaCodec`, `MediaMuxer`, `MediaExtractor`, `AudioRecord` and `AudioTrack`.

---

## Product decisions

These were chosen deliberately by the project owner. Keep them unless the owner says otherwise.

- **Local-only.** No accounts, no cloud sync, no analytics, no network permission. (Microphone, notifications and a foreground service are fine; they never leave the phone.)
- **One-stop shop:** illustrate, record and edit animations in one app, with **no limit on video length**.
- **Place or erase a color only.** No brush shapes, sizes or textures.
- **Alpha matters.** Image exports keep transparent backgrounds. Painting *replaces* a pixel (including its alpha); layers blend with normal "source-over" alpha. Video can't be transparent, so video export asks for a background color.
- **Canvas limit 256×256**, with presets 16/32/64/128/256. No frame limit.
- **Export scaling** is a whole-number multiple using nearest-neighbour, so pixels stay sharp (even multiples for video, so colors line up with H.264's 2×2 color blocks).
- **Mirror symmetry** "horizontal" means what happens on the left happens on the right (and vertical = top/bottom).
- **Merging layers asks for confirmation** and can be undone, including from inside the layers sheet.
- **Selection:** box or paint mode. A paint stroke that *starts* on a selected cell removes cells instead of adding them. Pasted/moved cells float until you tap ✓ or ✕. The clipboard is shared between projects.
- **Animation:** all frames share the same layers. Timing = one fps (1–60) for the whole animation plus any per-frame hold (1–999). Helpers: onion skin, playback, duplicate frame, reorder frames. **Duplicate can always go to the end** (setting).
- **Timeline:** frame ranges (copy/paste/move/delete/duplicate/reverse), zoom + scrub + waveform, insert before/after, cross-project frame copy.
- **Sound:** several tracks of clips (move, trim, volume), import from files, record with the mic as a voice-over or at a frame. Sound is positioned in **milliseconds**, so changing fps never shifts it. An option loops a short animation to fill a long sound.
- **Sound editing:** a **full-screen sound mode** of the timeline. Deleting a section **leaves a gap**; "Close gap" is a separate action. Volume, fades, the volume line and crossfades don't change the sound files. Effects and noise cleanup make a new sound file, and the clip switches to it (undo switches back).
- **AI noise removal is built in** (RNNoise, about 90 KB, works offline). Phone makers' AI audio erasers can't be used by other apps.
- **Export formats:** single PNG, PNG per layer, animated GIF, sprite sheet PNG, PNG per frame, **MP4** and **MOV** video with sound.

---

## Status

Everything above is implemented and covered by 113 JVM unit tests: engine, undo/redo, storage and
migration, frame ranges and the clipboard, timing, GIF (decoded back with `javax.imageio`), video
layout / color conversion / MOV patching, resampling, mixing, and sound files. For sound editing
they cover:
- every section edit (do → undo → redo)
- fades, crossfades and the volume line
- the effects: filter response, the gate, and time stretch keeping length, timing and pitch
- the noise removal: it matches the original RNNoise to −75 dB (the reference is ffmpeg's
  `arnndn` running the same weights)

**Checked by hand on a Samsung Galaxy S25 Ultra:**
- **Migration:** the owner's projects converted, with every pixel compared.
- **Timeline:** the expanded timeline, range select / reverse / copy / paste at end.
- **Video:** MP4 and MOV export, checked with `ffprobe`/`ffmpeg` for streams, BT.709 tags, duration, every frame's content, and sharp edges.

**On-device tests (`MediaDeviceTest`, all 12 passing on the same phone):**
- **Sound import:** WAV and FLAC at 44.1 kHz, MP3, AAC (.m4a) and Opus (.ogg) all decode at the right pitch, with left and right in the right order. Each starts within 0.01 ms of where it should.
- **Video with sound:** MP4 and MOV, with the sound in sync to within 0.01 ms. On the same files, ffmpeg's decoder measured the same timing as Android's, so the sync fix doesn't depend on Android's player. Holds, low fps, the animation repeating under a longer sound, and 4K (3840×2160) were also checked.
- **Preview clock:** runs at real time (48 000 frames/s) and never steps back. It reports the output timestamps that voice-over alignment uses.
- **Fades and crossfades** in an exported video match the expected levels to within 0.4%.
- **AI noise removal speed:** 60 s of mono sound in 4.3 s, stereo in 8.0 s, with steady noise 37 dB quieter.

**Sound screens, used on the same phone** (in a test project; clip positions read back from the saved project):
- **Import and clips:** importing through the file picker; nudge, split, move by drag (snaps to frames) and trim by dragging an edge, each with undo; deleting tracks.
- **Playback:** plays with sound; the playhead follows the sound past the end of a repeating animation.
- **Recording:** voice-over and "record here", with and without count-in.
- **Voice-over sync:** recorded beeps land within **0.5 ms** of the original beeps with the count-in, and within **1.6 ms** without it, using the correction saved from the count-in run. Before the count-in fix they landed **98 ms** late, because the phone's audio timestamps are off.
- **Export from the app:** an MP4 with sound in sync (checked with ffmpeg). A long 4K export kept running as a background foreground service, and Cancel removes the unfinished file.

**Sound editing, used on the same phone** (in a test project):
- **Sound mode:** deep zoom, taller tracks, waveform size.
- **Sections:** selecting, close gap, undo.
- **Levels:** fades, volume, the volume line, Normalize.
- **Effects:** +12 semitones moved a 1 kHz tone to 2 kHz, with timing within 8 ms.
- **Copy and paste;** punch-in with auto-stop.
- **Noise cleanup:** the Clean up noise (AI) sheet and its Before/After preview.

**Not yet tried:**
- headphones and Bluetooth (see below)
- pressing **Apply** in the Clean up noise sheet (it uses the same path as the other effects, which was tried)

### Known limitations

- **Undo history is not saved** with the project. It is lost when you leave the editor or the app is closed.
- **Fast diagonal strokes can leave small "L" corners** (there is no "pixel-perfect" stroke mode).
- **GIF export:** GIF has no partial transparency, so pixels with alpha < 128 become fully transparent and the rest become fully opaque. More than 255 colors are reduced to the most common. Frame timing is rounded to 1/100 s, and delays under 2/100 s are raised to that minimum, so 60 fps GIFs play slower.
- **Video:** H.264 only (no transparency). The output frame rate is at least 10 fps (lower project speeds repeat frames). Sound is lined up by measuring the phone's AAC encoder delay (43 ms on the test phone) the first time you export. If a phone's video writer can't record that delay in the file, the first ~43 ms of sound is left out instead, to keep sync.
- **Sound** is stored as uncompressed 48 kHz PCM (about 5.8 MB per minute mono, 11.5 MB stereo). Scrubbing the timeline doesn't play sound.
- **Recording timing:** Android's audio timestamps can be about 0.1 s off, so recordings are lined up by the count-in ticks the microphone hears.
  - **Count-in off:** it uses the correction measured the last time the count-in ran with the same speaker and microphone.
  - **Headphones:** the microphone can't hear the ticks, so nothing can be measured. PixelGo says so after recording, and the nudge buttons fix any offset.
- **Speed and pitch effects** (time stretch) blur sharp attacks by about 8–10 ms, which is normal for this kind of stretching. "Tape style" (plain speed change) doesn't.
- **Noise cleanup is made for voices over steady noise** (fans, hum, traffic, hiss).
  - It treats music as noise and damages it.
  - Sudden noises (a door, a dog) mostly stay.
  - At full strength voices can sound a little thin; lower strength keeps some background.
- **Effects and noise cleanup make new sound files.** The original is kept while undo might need it, and deleted after you leave the editor (undo history isn't saved).
- **The sound clipboard** is in the app's cache: it survives closing the app, but Android may clear it when storage is low.
- **Background:** recording and preview stop when the app goes to the background (Android silences the mic). Video export keeps going.
- **"Each layer" export** uses the current frame only. The **gallery thumbnail** shows frame 1.
- **Frames** move by buttons/menus in the timeline, not by dragging.
- **Layer limit:** 32. **Canvas limit:** 256×256.
- **No release build setup** (signing, R8/minify, store listing).

### Ideas for next steps

- Recording timing with headphones (a one-time "play through the speaker" check could measure it), and Bluetooth headsets.
- Persist undo history.
- Pixel-perfect stroke option.
- Drag-to-reorder frames; scrub with sound.
- Sound: a spectrum view, more effects (reverb, compressor), noise cleanup while recording.
- Selection: flip/rotate, mirror-aware paste.
- More tools: line, rectangle, ellipse, color replace.
- Import a PNG / sprite sheet / GIF as layers or frames.
- Release signing + Play Store listing; Compose UI tests.

---

## More documentation

- **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**: data model, storage and saving, editing engine, undo, timeline, sound, video export, file formats, and Compose pitfalls specific to this codebase.
- **[CLAUDE.md](CLAUDE.md)**: short working notes for AI-assisted development (also useful for humans).
