# PixelGo architecture

This document explains how the code fits together, so you can change it safely.
It assumes you have read the [README](../README.md).

```
 Compose UI (ui/…)  ──calls──▶  EditorViewModel  ──calls──▶  EditorEngine ──mutates──▶ PixelProject (metadata)
        ▲                          │      │                       │                       + CelStore (pixels)
        │ reads state ticks        │      │                       └── composite / dirty
        └──────────────────────────┘      ├─▶ ProjectRepository (folders, saving, migration)
                                          ├─▶ PreviewPlayer (sound + playback clock)
                                          └─▶ ExportJobs ─▶ ExportService (foreground) ─▶ VideoExporter / PngExporter
```

The rule of thumb: **all project changes go through `EditorEngine`**, which applies them and records
an undo entry. The view model turns engine results into Compose state and talks to Android
(files, audio, video). The UI only reads state and calls view-model functions.

---

## 1. Data model (`model/`)

| Type | Meaning |
|---|---|
| `PixelProject` | **Metadata only:** `width`, `height`, `layers` (index 0 = bottom), `frames`, `activeLayerIndex`, `currentFrameUid`, `fps` (1–60), id counters, and sound (`tracks`, `clips`, `assets`, `loopToAudio`). |
| `Layer` | `id` (stable for the project's lifetime), `name`, `visible`. **No pixels.** |
| `Frame` | `uid` (stable even when frames move), `hold` (1–999 ticks). Change holds via `project.setHold()`. |
| `SoundTrack` | `id`, `name`, `muted`, `volume` (immutable data class; edits replace it). |
| `AudioClip` | `trackId`, `assetId`, `startMs`, `inMs`..`outMs` (the trim), `volume`. **Times are in milliseconds** so changing fps never moves sound. |
| `AudioAsset` | A sound stored with the project (`audio/<id>.pcm`): `name`, `durationMs`, `channels`. |
| Pixels | A **cel** = one layer on one frame = `IntArray(width*height)` of non-premultiplied ARGB (`0` = transparent), stored in a `CelStore` under `CelKey(layerId, frameUid)`. A missing cel is fully transparent. |

**The current frame is tracked by uid** (`currentFrameUid`), so moving or reversing frames keeps
the user on the same picture. `currentFrameIndex` is derived from it through a cached
uid → index map.

**Frame list changes** must go through `insertFrames` / `removeFrames` / `moveFrames` /
`reverseFrames` / `setHold`. These bump `framesVersion` and invalidate the index map and
`timing`. **Layer list changes** must call `touchLayers()`, which bumps `layersVersion` (thumbnails
use it). **Sound changes** call `touchAudio()`.

**Timing** (`editor/Timing.kt`): 1 tick = 1/fps second. It keeps prefix sums of the holds, and
`frameAtTick` is a binary search. Everything uses `Long`, because 10 000 frames × hold 999 × 1000
overflows `Int`. `project.totalMs()` = the animation length, or with `loopToAudio` the longer of
the animation and the end of the last unmuted sound.

---

## 2. Pixel storage (`editor/CelStore.kt`, `data/DiskCelStore.kt`)

Animations can be far larger than memory, so pixels live in a `CelStore`:

- `read(layer, frame)` returns the array, or null if the cel is empty. Don't modify it.
- `edit(layer, frame)` returns the array for writing, creating it if needed, and marks it dirty with a new version number. Call it before *every* write.
- `put(key, pixels)` and `copy(src, dst)` write straight to disk. They are for bulk jobs, so big jobs don't pile up in memory.
- `pin(keys)` keeps the current frame's cels in memory.

**`DiskCelStore`** stores one file per cel (`cels/<layerId>_<frameUid>.cel`: magic, size, deflated ints) and keeps an LRU cache of about 48 MB:
- Clean cels are evicted when the cache is full.
- Dirty and pinned cels are never evicted.
- Empty cels have no file, and an index of the files present avoids disk checks.
- Every method is `synchronized`: the UI thread edits while the IO, thumbnail, playback and export threads read.

**`MemoryCelStore`** is the in-memory version used by unit tests.

### Saving (the protocol matters)

`EditorViewModel.save()` runs on the **UI thread** (where edits happen):
1. **Snapshot the changes.** `store.snapshotDirty()` copies each dirty cel with its version number. If cells are mid-move, the lifted pixels are put back into that cel's copy.
2. **Collect references.** The layer ids, frame uids and sound asset ids used by the project **and by its undo/redo history** (`engine.collectRefs`, `collectAssetRefs`). A recording in progress counts as used.
3. **Serialize the meta.** It's written with `MetaSerializer`, and the id counters are taken as upper bounds.

`ProjectRepository.save()` then works on its **single IO thread**:
1. Write the cels: each goes to a `.tmp` file and is renamed into place, and an all-transparent cel deletes its file.
2. Write `project.meta` the same way.
3. Garbage-collect cels whose layer or frame isn't referenced, and sound files whose asset isn't. Ids at or above the bounds were created after the snapshot, so they are **kept**.
4. Write `thumb.png` (frame 1).
5. Call `store.markClean(snapshot)`, which clears a cel's dirty flag **only if its version didn't change meanwhile**.

**Crash safety:** cels are written before the meta, so a crash can leave extra cel files but never
missing ones. **On open**, the repository deletes every cel and sound file the meta doesn't
reference. History is empty at load time, and this also removes leftovers from bulk jobs
interrupted by a crash.

**Bulk jobs** (`BulkJob`: duplicate layer, merge down, range duplicate / paste, clipboard copy):
`work` runs off the main thread with a progress overlay and writes cels directly. `finish` then
runs on the main thread and pushes the `Edit`. While a job runs, editing and saving are blocked
(`isBusy`). Small jobs run inline without the overlay.

**Merge down** writes the result into a **new layer id**. `MergeDownEdit` swaps the two originals
back on undo, so no pixel copies are kept, even across thousands of frames.

---

## 3. Editing engine (`editor/EditorEngine.kt`)

- **Composite:** `composite` is the current frame's visible layers, flattened. `visCels` caches the current frame's cel arrays for fast per-pixel updates. `markDirty(i)` recomputes one pixel, and structural changes call `refreshCels()`. The view model copies the changes into its bitmap with `consumeDirty`.
- **Strokes:** `beginStroke` / `strokeTo` / `endStroke` record one `PixelEdit(layerId, frameUid, …)`. `cancelStroke` restores the pixels (used when a second finger lands for pinch-zoom). Bresenham lines fill the gaps between touch points, and `Raster.forEachMirrored` applies symmetry.
- **Fill and eyedropper:** scanline flood fill on the active layer. The eyedropper reads the composite.
- **Selection** (`Selection.kt`): selections are any shape. Copy and cut produce a `Clip`. `beginMove` lifts the pixels into a `Floating` (layer + frame uid + original values); `commitFloating` writes them down as **one** `PixelEdit`, and `cancelFloating` restores them.
- **Frames:** add (before/after/end), duplicate (after or to end), delete, move, and holds, plus range operations: `deleteFrames`, `reverseFrames`, `moveFrames`, `setHolds`, `duplicateFramesJob`, `frameRange` (for the clipboard), and `pasteFramesJob`. Pasting maps layers by position, adds missing layers, and centers or crops other canvas sizes.
- **Sound:** `newAssetId` / `addAsset` (files are registered after they're written), `addClip` (optionally on a new track), `setClipLive` + `commitClip` (live dragging = one undo step), `changeClip`, `splitClip`, `duplicateClip`, `deleteClip`, `addTrack`, `changeTrack`, `deleteTrack` (one undo step that brings the clips back too).
- **Sound sections** (a time range on a set of tracks; each call is one `CompoundEdit`):
  - **Cutting and pieces:** clips are split at the section's edges (`splitInto`), then the pieces inside are acted on. `piecesIn` returns copies of what's inside, with `DECLICK_MS` (5 ms) fades at cut edges so cuts never click.
  - **Deleting:** `deleteSection(…, closeGap)` leaves a gap. With `closeGap` it also pulls everything later on those tracks left (a ripple delete). `insertSilence` pushes it right.
  - **Copying in place:** `duplicateSection` puts a copy right after the section. `placeOver(pieces)` lays clips over a range, removing what was there (paste, punch-in).
  - **Levels:** `scaleSection(from, to, gainFrom, gainTo)` writes volume-line points (fade in/out = 0→1 / 1→0, or a flat volume). A section that starts or ends at a clip's edge, within 20 ms, becomes that clip's fade instead.
  - **Effects:** `useProcessedSound` points a clip at a processed copy of its sound, scaling or mirroring fades and the volume line to match.
- **Grouping:** `beginGroup` / `endGroup` / `cancelGroup` make several engine calls one undo step. Applying effects uses this: split the section, then switch each piece to its new sound once the background work finishes. `cancelGroup` undoes everything done since `beginGroup` if that work fails.

### Undo system (`editor/History.kt`)
- `Edit.undo/redo(project, store)`. **Edits are pushed after the change has already been applied.**
- Each edit reports what it may bring back (`collectRefs`, `collectAssetRefs`), so saving never deletes data that undo or redo still needs.
- `PixelEdit` jumps to its frame (by uid) and layer when undone.
- Frame edits:
  - `InsertFramesEdit` and `DeleteFramesEdit` keep only the `Frame` objects; the cels stay in the store.
  - `MoveFramesEdit` and `ReverseFramesEdit` only reorder.
  - `SetHoldsEdit` changes holds.
- Layer edits: `AddLayerEdit`, `DeleteLayerEdit`, `MoveLayerEdit`, `MergeDownEdit`, `LayerPropsEdit`.
- Sound edits: `AddClipEdit`, `RemoveClipEdit`, `ChangeClipEdit`, `AddTrackEdit`, `RemoveTrackEdit`, `ChangeTrackEdit`.
- `CompoundEdit` groups several edits into one step (e.g. a paste that adds layers, or a split).
- The stack holds 100 entries and lives in memory only.

**Adding a new editing feature:** add an engine method that mutates the project and pushes an `Edit`
(a new subclass if needed, which must report its refs). Add a view-model wrapper that calls
`changed(structure = …)`. Then add a JVM test covering do → undo → redo.

---

## 4. View model (`ui/editor/EditorViewModel.kt`)

- **Loading:** the project is loaded on the repository thread (`LoadState.Loading` → `Ready` / `Failed`). The view model owns the engine, the `DiskCelStore`, and a bitmap that mirrors `engine.composite`.
- **State ticks:** `renderTick` (pixels on screen) and `structureTick` (layers, frames, sound, names). The model is mutated in place, so the UI reads these ticks.
- **Guards:** changing tool, layer or frame, or running any operation, first commits floating cells. Undo while cells are floating cancels them instead. Touching the canvas during playback stops it; during recording it does nothing.
- **Playback:**
  - `startPlaying()` builds an `AudioMixer` over the project's sounds and starts a `PreviewPlayer`. The `AudioPlaybackClock` chooses the frame from **what's being heard**: the frame on screen is `frameAtTick(clockTicks % animationTicks)`, so the animation repeats under a longer sound.
  - `playTick` is the unwrapped position, so the playhead runs along a long sound; `playIndex` is the frame on screen.
  - The next two frames are rendered ahead in the background.
- **Recording** (`startRecording` / `stopRecording` / `lineUp`):
  - **Why not timestamps:** on the test phone, `AudioRecord` and `AudioTrack` timestamps were **75–98 ms off**, depending on the recording source, which is too much for lip sync. So the recording is lined up acoustically.
  - **Start:** the `AudioRecorder` starts **first**. Then, for a voice-over, playback starts from the current frame; for "record here" with a count-in, a count-in-only player starts. With the count-in on, the player first plays `CountIn.sound()`: three 1 kHz ticks at 200 / 1200 / 2200 ms, with the start on the 4th beat at 3200 ms. Meanwhile the clock holds on the start frame and the sheet shows 3-2-1 from what is heard.
  - **Stop, lining up:** `CountIn.locate` finds the three ticks, evenly spaced, in the first seconds of the recording. That gives the exact recording position of the start frame. The count-in part is cut off with the clip's `inMs`, and `addClip(…, startMs, inMs)` places the clip.
  - **Learned correction:** the gap between that and the timestamps' prediction is saved per output/input device route (`AppSettings.recordCorrectionMs`).
  - **Fallbacks:** without a count-in, or with headphones where the ticks can't be heard, it uses the timestamps minus the saved correction. If there is no correction, it says so in a toast.
  - **Measured on device:** 0.5 ms off with the count-in, 1.6 ms with a saved correction.
  - **Defaults:** recordings go to a new track if the selected one already has sound from the current frame on. Names don't repeat (`Names`).
  - Both stop on `ON_STOP`.
- **Import:** `importAudio(uri)` runs `AudioDecoder` (background, progress overlay), then adds the asset and a clip at the current frame.
- **Sound mode state:**
  - `soundMode`, `soundTool` (Move / Select / Volume), `cursorMs`, and `soundSel` (tracks + from/to ms).
  - `cursorMs` is the sound cursor in milliseconds; `soundCursorMs()` falls back to the current frame's start, and `selectFrame` resets it.
  - Section actions (`splitAtCursor`, `deleteSelection`, `fadeSelection`…) are thin wrappers around the engine's section calls.
- **Effects** (`applyEffects(clipId or selection, EffectSettings)`):
  - On the UI thread: `beginGroup`, split the section, and pick new asset ids.
  - On IO: render each piece's in..out range through `Effects.chain` with `SoundRender.process` into new `.pcm` + `.peaks` files, with progress.
  - Back on the UI thread: `addAsset` + `useProcessedSound` per piece, then `endGroup`. On failure: `cancelGroup` and delete the new files.
- **Noise cleanup:**
  - It's `EffectSettings(denoise = strength)` through the same path. The `denoiser` makes a `DenoiseStage`.
  - The weights (`assets/rnnoise.bin`) load lazily, on the first background use.
  - `previewCleanup` renders up to 5 s (from the cursor, or the selection's start) into memory, raw or cleaned, and plays it with its own `PreviewPlayer`.
  - `cleanupPreview` / `cleanupPreparing` drive the sheet's buttons.
- **Sound clipboard:**
  - `copySelection(cut)` mixes each selected track's section (clip volumes, fades and volume line, not track volume) with `SoundRender.mix` into `SoundClipboard`.
  - `pasteSound()` imports those files as new assets and `placeOver`s them at the selection start or cursor, on the selected track and the ones below (adding tracks if needed). One undo step.
- **Punch-in** (`startPunchIn`):
  - Needs a selection on one track. It records like a voice-over from `PUNCH_PREROLL_MS` (2 s) before the section, and stops itself 1 s after the section ends.
  - On stop, the take is lined up exactly like a voice-over (count-in). Only the section's part is `placeOver`ed, with 10 ms fades.
- **Autosave:** 2 s after the last edit, and on `ON_STOP` and back. Postponed while busy.
- **Undo offers:** `undoable(message) { … }` runs a delete and, if it added an undo step, shows "Deleted … · Undo" in `snackbar` (shown by `EditorScreen`). Undo only acts while that step is still `History.lastEdit`; any later edit withdraws the offer.
- **Look and layout:** screens use the tokens in `ui/theme/Theme.kt` and the components in `ui/common/Components.kt` (see [DESIGN.md](DESIGN.md)). `EditorScreen` switches to rails beside the canvas when the screen is wider than tall.

---

## 5. Timeline (`ui/editor/TimelineView.kt`)

It's one `Canvas` that draws **only what's visible**: the frames between `frameAtTick(scroll)` and `frameAtTick(scroll + width)`, and the clips inside the visible time range.

- **Header and handle:** compact shows play, the filmstrip, onion skin, add and duplicate; full shows play and time, onion skin, select frames, add, duplicate and sound mode. The handle on top switches between them (tap, or drag up / down). Record and import live in sound mode and the track menu.
- **Size:** a frame's width = `hold × zoom` (zoom = screen pixels per tick). Thumbnails are 64 px composites cached in the view model, keyed by `(frameUid, frameVersion, layersVersion)`, and rendered on `Dispatchers.Default`.
- **Gestures** (one `awaitEachGesture`):

  | Gesture | Where | Does |
  |---|---|---|
  | Tap | Frame | Selects it, or extends the range in range mode |
  | Tap | Clip | Opens the clip sheet |
  | Tap | Track name | Opens the track menu |
  | Tap | "+ Add track" row | Adds a track |
  | Long-press | Frame | Frame menu |
  | Long-press + drag | Clip | Moves it (snaps to frame starts within 6 dp, can change track), or trims at its edges (14 dp) |
  | Drag | Anywhere | Scrolls (with fling); in the sound rows, vertical drags scroll the tracks |
  | Drag | Ruler | Scrubs |
  | Pinch | Anywhere | Zooms around the fingers |
- **Waveforms** come from `.peaks` files (10 ms min/max), drawn about every 2 dp.
- **Animation end:** the area past the end of the animation is shaded. A green tint means the animation repeats there ("loop until the sound ends"); a dark shade means the sound is cut off there.
- **Sound mode** (`vm.soundMode`; `EditorScreen` gives the timeline the whole screen, with `SoundModeHeader`, `SoundToolsRow` and `SoundSelectionBar` from `SoundModeBars.kt`):
  - **Zoom and height:** `maxZoom` goes to 4 px per sample. Track height is `AppSettings.trackHeightDp` (40–240 dp); a pinch that's more vertical than horizontal changes it.
  - **Waveforms:** drawn at `waveScale` (×1–×16), with real min/max shapes. Closer than the 10 ms peaks, they come from `WaveformCache`: 1-second blocks of samples, LRU, loaded on IO. At a few pixels per sample the waveform is the sample line itself.
  - **Taps** set the cursor (snapped to frame starts and clip edges within 6 dp). With the Select tool a drag across rows makes `soundSel`, and its edges can be dragged.
  - **Direct targets** (`soundTarget`) are checked before other gestures. They are fade handles (Move tool, top corners of a clip), volume-line points (Volume tool: drag to move, long-press to remove; a tap on a clip adds one), and selection edges. Live changes use `moveClipLive`, so a drag is one undo step.
  - **Level drawing:** clips show fades and the volume line as `ClipGain` computes them (the same code the mixer uses), so what you see is what you hear.
- **Playhead:** while playing it is drawn at `vm.playTick`, and the time readout shows it too, so it moves on past a repeating animation. The timeline scrolls to keep it on screen (`snapshotFlow`). When not playing, it marks the current frame.

---

## 6. Sound (`editor/AudioMixer.kt`, `editor/Resampler.kt`, `data/audio/`)

- **Format:**
  - Every sound is converted once to `audio/<assetId>.pcm`: a 16-byte header (`PCM1`, rate, channels), then 16-bit little-endian **48 kHz** samples, mono or stereo.
  - Alongside it, `.peaks` holds one signed (min, max) byte pair per 10 ms.
  - `PcmFile` reads from any position (`PcmSource.readStereo`).
- **Import** (`AudioDecoder`):
  - `MediaExtractor` + `MediaCodec` decode the first audio track.
  - The sample rate, channel count and `KEY_PCM_ENCODING` (8/16/24/32-bit or float) are read from the decoder's output format.
  - Surround is folded to stereo.
  - Anything that isn't 48 kHz goes through `Resampler`: windowed sinc, 32 taps, Blackman window, 512 phases, with exact output length and an aligned start.
  - **Don't trim the encoder delay yourself.** The extractor's format carries `encoder-delay` / `encoder-padding` (MP3 LAME tags, MP4 edit lists), and the codec trims them itself because that format is passed to `configure`. Trimming again made MP3s start 13 ms early and AAC 21 ms early. `MediaDeviceTest` checks each format starts on time.
- **Mixing** (`AudioMixer`):
  - Takes snapshots of the tracks and clips.
  - Per clip, the gain comes from `ClipGain`; muted tracks are skipped and trims applied:
    - clip volume (up to 4×) × track volume × volume line (`GainPoint`s in *source* ms, so they stay on the sound when the clip is trimmed or split, linear between points) × S-curve fades
    - × **equal-power crossfades** where a clip starts inside the previous one on its track and ends after it
    - Flat clips take a fast path.
  - Output is a hard-clamped 16-bit sum.
  - The same mixer feeds preview and export.
- **Preview** (`PreviewPlayer`):
  - A thread feeds an `AudioTrack` (stream mode) continuously, with silence where there are no clips, so the clock never stalls.
  - A list of (written frame → timeline frame) segments handles looping.
  - The position comes from `AudioTrack.getTimestamp` (checked every 50 ms, interpolated with `nanoTime`), falling back to the playback head.
- **Preview count-in:** `PreviewPlayer(…, preRoll)` writes the count-in before the timeline. `heardFrames()` drives the 3-2-1 display, and `outputDeviceType()` keys the saved correction.
- **Effects** (`editor/audiofx/Effects.kt`, pure Kotlin):
  - A `Stage` takes interleaved float frames and emits frames (the count can differ). `Effects.chain` builds, in order: noise removal, noise gate, EQ (low cut + bass/mid/treble biquads, RBJ formulas), time stretch, resample.
  - **Speed and pitch:** pitch = WSOLA time stretch by pitch/speed, then a resample by the pitch ratio. Tape style = resample only.
  - **WSOLA:** 40 ms windows, 20 ms hop, ±7.5 ms search. The output is centered so onsets don't move (tested to a few ms), and it starts on silence padding so the first window is whole.
  - **Noise gate:** 5 ms lookahead, 60 ms hold, 40 ms close.
  - **Rendering:** `SoundRender.process` streams a source range (optionally reversed) through the stages into a `PcmWriter`, with progress and cancel.
- **AI noise removal** (`editor/denoise/RnNoise.kt`): a line-by-line Kotlin port of RNNoise v0.1.1 (xiph, BSD-3), running the original weights.
  - **Weights:** `tools/rnnoise_weights.py` converts the C weight tables into `assets/rnnoise.bin`. The file is `RNN1` followed by 6 layers of int8 weights, read by `RnnModel`.
  - **Signal path:** 48 kHz, 480-sample frames, input on RNNoise's 16-bit scale.
  - **Analysis:** a mixed-radix FFT (960 points, forward unscaled; the 1/N is applied like the C code), 22 band energies + DCT features, pitch search and the pitch filter.
  - **Network:** a GRU network gives per-band gains (and voice activity), interpolated and applied, then overlap-add.
  - **`DenoiseStage`:** one RNNoise state per channel. It removes RNNoise's 480-sample delay, so the output lines up with the input and has exactly its length.
  - **Strength** sets a gain floor of `10^(−2·strength)`, so 50% removes at most 20 dB; 100% = no floor.
  - **Reference test:** `RnNoiseTest` compares with ffmpeg's `arnndn` running the same weights. ffmpeg's port skips the 1/N FFT scaling and feeds ±1 samples, so the reference is made with the input scaled by 32768/960 and the output scaled back (commands in `tools/rnnoise_reference.py`); then the two match to −75 dB.
- **Recording** (`AudioRecorder`): an `AudioRecord` (source `MIC`) at 48 kHz mono feeds a `PcmWriter`. `getTimestamp(MONOTONIC)` gives a capture time for the first sample (`timestamped` says whether it's real), but it isn't trusted for sync; see the recording section in §4. It needs `RECORD_AUDIO`, which is requested on first use.

---

## 7. Video export (`data/video/`, `export/`)

- **`VideoLayout`:**
  - Picks the **largest even whole-number scale** that fits the size preset, with a minimum of 2; the video grows if needed.
  - Shapes are canvas, 16:9, 9:16 or 1:1, padded with the background color, and offsets are even.
  - The encoder's `areSizeAndRateSupported` decides the final size, falling back to a multiple of 16 and then to smaller presets.
- **`YuvFrame`:**
  - Flattens a frame onto the background color.
  - Converts it with **BT.709 limited range**, using integer weights. Each color row sums to 0, so greys stay neutral; a unit test checks this.
  - Pre-builds output rows, so filling a frame is mostly row copies. Because the scale and offsets are even, each art pixel covers whole 2×2 chroma blocks, which keeps edges sharp.
- **`VideoExporter`:**
  - **Encoders:** `MediaCodec` H.264 with `COLOR_FormatYUV420Flexible` input via `getInputImage`, honoring row and pixel strides. Color standard, range and transfer are signalled. AAC-LC runs at 48 kHz stereo, 192 kbps.
  - **Frame rate and length:** output fps = project fps × n, so it's at least 10. A frame held N ticks is repeated. `totalMs` can exceed one pass of the animation, which then repeats.
  - **Muxing:** whichever stream is behind in time is fed first. Outputs are drained without blocking. The `MediaMuxer` starts only after both formats arrive, and earlier output is buffered until then.
  - **Ending:** the stream ends with an EOS input buffer. Codec-config buffers are skipped.
  - **Sound sync (`AacPriming`):**
    - **The problem:** AAC encoders put a start-up delay ("priming") before the sound. It is 2048 samples (43 ms) on `c2.android.aac.encoder`, the encoder doesn't report it, and `MediaMuxer` doesn't record it, so without help the sound plays late.
    - **Measuring it:** once per app run, `AacPriming.get()` encodes a short test tone, decodes it, and measures the shift. It then muxes a probe file with the sound's timestamps starting at −delay, and checks whether `MediaExtractor` reads back an `encoder-delay`, meaning `MediaMuxer` wrote an MP4 edit list.
    - **Applying it:** if `MediaMuxer` wrote the edit list, the exporter subtracts the delay from the sound's timestamps when writing to the muxer, so players skip the priming and nothing is lost. If not, the mix starts `delay` frames in, which keeps sync but drops the first ~43 ms.
    - **Never shift the encoder's input timestamps:** Codec2 stores them as unsigned counters.
- **Output:**
  - `VideoOutput` creates a pending `MediaStore.Video` item in `Movies/PixelGo` (a plain file on API 26–28) and opens it `"rw"`.
  - For **MOV**, `MovPatcher` rewrites the `ftyp` brands to `qt  ` in place.
  - Finally `IS_PENDING` is cleared. Cancelling or failing deletes the item.
- **`ExportJobs` and `ExportService`:**
  - **`ExportJobs`** runs one export at a time on its own coroutine scope (not tied to any screen), with progress in Compose state. Cancelling raises `ExportCancelled`.
  - **`ExportService`** keeps the process in the foreground while it runs, using type `mediaProcessing` on API 35+, `dataSync` on 29–34, and none below. It shows a progress notification with a Cancel action, holds a partial wake lock, and handles `onTimeout`.
  - **Locking:** while a project exports, its editor is locked (overlay with progress / Share / OK) and the gallery blocks rename/duplicate/delete.

Image exports (`PngExporter`, `GifEncoder`) stream frames through a `(index) -> IntArray`
provider. GIF delays carry the rounding error forward, with a minimum of 2 cs.

---

## 8. Persistence formats

All project data is in app-private storage (`allowBackup` is off).

| What | Where |
|---|---|
| Project | `files/projects/<id>/project.meta` |
| Pixels | `files/projects/<id>/cels/<layerId>_<frameUid>.cel` |
| Sounds | `files/projects/<id>/audio/<assetId>.pcm` + `.peaks` |
| Gallery thumbnail (frame 1) | `files/projects/<id>/thumb.png` |
| Original of a migrated project | `files/projects/<id>.pxg.bak` (deleted with the project) |
| Palettes | `files/palettes.bin` |
| Settings | SharedPreferences `pixelgo` (`active_palette`, `duplicate_to_end`, `record_correction_<route>`, `wave_scale`, `track_height_dp`) |
| Frame clipboard | `cache/frame_clipboard/` (manifest written last; checked before pasting) |
| Sound clipboard | `cache/sound_clipboard/`: `<i>.pcm` + `.peaks` per track (48 kHz stereo), manifest `sound.meta` (`SCLP`, written last) |

### `project.meta` (format v3, `MetaSerializer`)
```
Header (uncompressed): int "PXG3", int 3, UTF name, int width, int height, long modified,
                       int frameCount, int fps, long totalTicks
Body (deflate): tagged sections  int tag, int length, bytes   …  int 0
  1 STATE : activeLayer, currentFrameUid, nextLayerId, nextFrameUid, fps
  2 LAYERS: n × (id, name, visible)
  3 FRAMES: n × (uid, hold)
  4 AUDIO : loopToAudio, next ids, tracks, assets, clips
  5 AUDIO_LEVELS: n × (clipId, fadeInMs, fadeOutMs, m × (atMs, gain))   only clips that use them
```
Readers skip unknown tags, so new sections don't need a migration. If the format ever has to
change incompatibly: bump the version, keep reading old ones, and add tests.

### Old `.pxg` (v1/v2) and migration
`LegacyPxg` reads the old single-file format, which had everything in memory and a 128-frame cap.
At startup `ProjectRepository` runs `Migration.migrate`:
1. Write a `<id>.migrating` folder.
2. Re-read the meta and every cel, and **compare them with the original**.
3. Rename the folder into place.
4. Only then rename `<id>.pxg` → `.pxg.bak`.

---

## 9. Compose pitfalls in this codebase

1. **Read a tick to observe model changes**, for example `vm.structureTick` in the layers sheet and timeline, or `vm.renderTick` inside `Canvas` draw blocks (so only drawing reruns).
2. **Gesture handlers outlive compositions.** `pointerInput` coroutines must read *live* values (`vm.timing()`, `vm.clips()`, state-backed vars) rather than values captured during composition. `clampScroll` in `TimelineView` reads the live length for this reason.
3. **In-place-mutated lists** never change identity. In lazy lists, read the state (`vm.frameCount`, `structureTick`) **inside** the content block.
4. Palettes are immutable `data class`es in `mutableStateOf`, so palette UIs update normally.

---

## 10. Testing

- `./gradlew testDebugUnitTest` runs 113 JVM tests:

  | Suite | Covers |
  |---|---|
  | `EngineTest` | Drawing, selection, layers |
  | `AnimationTest` | Frames, holds, insert/duplicate-to-end, meta round trip, GIF |
  | `StorageTest` | `DiskCelStore`: cache, dirty/version, garbage collection, copy; history-aware cleanup; migration; timing; 10 000 frames |
  | `RangeTest` | Range ops, clipboard across projects, layer mapping, size fit |
  | `VideoTest` | Layout, BT.709 conversion and neutrality, chroma alignment, MOV patch |
  | `AudioTest` | Resampler pitch, length, alias rejection and alignment; mixer; PCM files and peaks; sound edits and undo; clips with an in-point; finding the count-in in a noisy, echoing recording (and not in noise); default names; loop-to-sound length; meta round trip |
  | `SoundEditTest` | Fades (S-curve, shortened to fit), equal-power crossfades, the volume line staying on the sound; tag 5 round trip and old files; section edits (delete with soft edges, close gap, insert silence, duplicate, split) with undo; processed sound as one undo step, reversed levels; fade vs. volume line at clip edges |
  | `EffectsTest` | Low cut and 3-band EQ responses; noise gate; slower keeps pitch, octave up keeps length, sounds stay on the beat, stretched sound starts at full level; tape style; reverse and channel count; neutral settings |
  | `RnNoiseTest` | The FFT against a plain DFT; output vs. the ffmpeg reference (−60 dB required); noise removed with the voice kept; strength; stereo channels kept apart |
- Engine, storage and media math are plain Kotlin and JVM-tested. Code using `MediaCodec`, `AudioTrack` or `AudioRecord` needs a device.
- **On-device tests** (`app/src/androidTest/.../MediaDeviceTest.kt`, 12 tests; how to run them is in the README and CLAUDE.md, **never with `connectedAndroidTest`**, which uninstalls the app and deletes its projects). They use no screen and no projects; files go to `cache/devtest` and are deleted.
  - **The test signal:** one reference signal, a 1 kHz left / 500 Hz right burst from 0.5 s to 1.5 s with 2 ms fades.
    - It is generated in the test for WAV.
    - It is stored in `assets/` as MP3, AAC (.m4a), Opus and FLAC, made with ffmpeg; the commands are in the test's header.
  - **How timing is measured:** the point where a 2 ms RMS envelope crosses half its level, compared with the same measurement on the reference. It is phase-independent and accurate to about 0.01 ms.
  - **Covers:**
    - import of each format: timing, length, pitch, and left/right order
    - video export with sound: frame timing, sound sync with and without an edit list, MOV
    - holds at low fps, and 4K
    - the preview clock: real time, monotonic, timestamps available
    - fades and crossfades in an exported video, against `ClipGain`, every 50 ms
    - AI noise removal speed on the phone (60 s of mono and stereo; target under 10 s for mono) and that it still removes noise there
  - Add `-e keep true` to keep the exported videos, then pull them with `adb exec-out run-as com.pixelgo cat cache/devtest/export.mp4 > export.mp4`.
- **Manual device checks** (use a separate, clearly named test project; see CLAUDE.md):
  - **Drawing:** pinch mid-stroke; move with select ✓/✕.
  - **Timeline:** ranges, clipboard paste in another project.
  - **Sound screens:** import through the file picker, record a voice-over and "record here", drag and trim clips, play with sound.
    - **Exact clip positions:** read them from the saved `project.meta`; it's a small parser away (tagged sections, zlib).
    - **Voice-over sync:** play a sound with 1 kHz beeps on the speaker while recording, then compare beep times in the recording with the music's. **Do this analysis on the phone**, with a temporary instrumented test that runs in the app's process, and print only the beep times. Never copy a microphone recording off the phone: it contains the room.
  - **Video:** export MP4/MOV from the app. Then pull it and check with `ffprobe -show_streams` (both tracks, BT.709, duration) and `ffmpeg -i x.mp4 f_%03d.png` (frame content, sharp edges).
  - **Background:** a long export continues with the app in the background, and Cancel works.
