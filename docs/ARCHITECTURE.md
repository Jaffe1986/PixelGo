# PixelGo architecture

This document explains how the code fits together, so you can change it safely.
It assumes you have read the [README](../README.md).

```
 Compose UI (ui/…)  ──calls──▶  EditorViewModel  ──calls──▶  EditorEngine  ──mutates──▶  PixelProject
        ▲                             │   ▲                       │
        │ reads state ticks           │   └── composite/dirty ────┘
        └─────────────────────────────┘
 ProjectRepository / PaletteRepository / PngExporter (data/…)  ◀── load, save, export
```

The rule of thumb: **all project changes go through `EditorEngine`**, which applies them and records
an undo entry. The view model turns engine results into Compose state. The UI only reads state and
calls view-model functions.

---

## 1. Data model (`model/Model.kt`)

| Type | Meaning |
|---|---|
| `PixelProject` | `width`, `height`, `layers` (index 0 = bottom), `frames`, `activeLayerIndex`, `fps`, `nextLayerId`, and a shared `FrameCursor`. |
| `Layer` | `id` (stable for the project's lifetime; undo entries refer to it), `name`, `visible`, `cels: MutableList<IntArray>`: **one pixel buffer per frame**. |
| `Layer.pixels` | A **getter** returning `cels[cursor.index]`, i.e. the current frame's buffer. Most drawing code just uses `layer.pixels`. |
| `FrameCursor` | One per project, shared by all its layers. `project.currentFrame` reads/writes it. Changing frames is just changing the cursor. |
| `Frame` | Per-frame settings. Currently only `hold` (how many fps ticks it stays on screen). |
| `Palette` | Immutable `data class` (`id`, `name`, `colors`). |

**Pixel format:** every pixel is a non-premultiplied ARGB `Int` (`0xAARRGGBB`), and `0` means fully transparent.
**Painting replaces** the pixel value in the layer (alpha included). **Compositing** blends visible layers
bottom-to-top with source-over (`ColorMath.blend`, integer math).

**Invariant:** every layer has exactly `frames.size` cels. Anything that adds or removes frames must
update every layer (see `FrameOps` in `History.kt`). New layers get a cel per frame (`project.newLayer`).

**Copies:** `PixelProject.deepCopy()` creates a **new** `FrameCursor` and re-binds every copied layer
to it. Never share `Layer` objects between two projects.

Limits live at the top of `Model.kt`: `MAX_CANVAS_SIZE = 256`, `MAX_LAYERS = 32`, `MAX_FRAMES = 128`,
and `MAX_TOTAL_CELLS = 24_000_000` (layers × frames × w × h, about 96 MB of pixels).

---

## 2. Editing engine (`editor/`)

`EditorEngine` is plain Kotlin with no Android dependencies. That is why almost everything can be unit tested on the JVM.

### Composite cache and dirty tracking
- `composite: IntArray` is always the flattened **current frame** (visible layers only).
- Single-pixel changes call `markDirty(i)`, which recomputes `composite[i]` and records `i`.
- Structural changes (layers, frames, visibility, undo of non-pixel edits) set `fullDirty`.
- The view model calls `consumeDirty(onFull, onPixel)` after each operation to copy changes into its `Bitmap`. Small strokes update only the touched pixels.

### Strokes
`beginStroke(x, y, color)` → `strokeTo(x, y)`… → `endStroke()` (records one `PixelEdit`), or
`cancelStroke()` (restores pixels and records nothing; used when a second finger lands for pinch-zoom).
Points between touch samples are filled with Bresenham (`Raster.line`). Each point is expanded through
`Raster.forEachMirrored` for symmetry. The eraser is a stroke with color `0`.

### Fill and eyedropper
`fill()` is a scanline flood fill on the active layer (exact ARGB match), run once per mirrored seed.
`pick()` reads the **composite**, i.e. what you see.

### Selection (`Selection.kt` + engine)
- `selection: BooleanArray?`, one flag per pixel (any shape). `selectRect`, `beginSelectPaint` / `selectPaintTo` (a stroke that starts on a selected cell removes cells).
- `copySelection()` returns a `Clip` (cropped to the bounding box, with a mask). `cutSelection()` = copy + `deleteSelection()` (undoable).
- **Floating cells** (`Floating`): `beginMove()` lifts selected pixels off the layer (they are cleared but *not* recorded yet, and the originals are kept in `lifted`). `beginPaste(clip)` floats a clip. `moveFloatingTo()` drags. `commitFloating()` writes it down as **one** `PixelEdit` (lift + drop together). `cancelFloating()` restores everything. Fully transparent clip cells don't overwrite the destination.
- Selection changes are **not** in the undo history (common editor behaviour).

### Layers and frames
Engine methods (`addLayer`, `mergeDown`, `addFrame`, `duplicateFrame`, `moveFrame`, `setFrameHold`, …)
apply the change, then `history.push(...)` an `Edit` that can reverse it. `mergeDown` merges **all frames**.

### Undo system (`History.kt`)
- `Edit` has `undo(project)` and `redo(project)`. **Edits are pushed after the change has already been applied.**
- `PixelEdit(layerId, frame, indices, before, after)`: undoing it **jumps to that frame and layer**, so the user sees what changed.
- Structural edits: `AddLayerEdit`, `DeleteLayerEdit`, `MoveLayerEdit`, `MergeDownEdit`, `LayerPropsEdit`, `AddFrameEdit`, `DeleteFrameEdit`, `MoveFrameEdit`, `FrameHoldEdit`. They hold object references (e.g. the deleted `Layer`), which is safe because undo/redo always run in stack order.
- The stack holds 100 entries and lives in memory only (it is not saved).

**Adding a new editing feature:** add an engine method that mutates the project and pushes an `Edit`
(new `Edit` subclass if needed), add a view-model wrapper that calls `changed(structure = …)`, then
add a unit test in `EngineTest` / `AnimationTest` covering do → undo → redo.

---

## 3. View model (`ui/editor/EditorViewModel.kt`)

- Loads the project, owns the `EditorEngine` and a `width × height` `Bitmap` that mirrors `engine.composite`.
- **State ticks:** `renderTick` (pixels on screen changed) and `structureTick` (layers/frames/names changed). The model objects are **mutated in place**, so Compose cannot see those changes by itself. UI code reads these ticks to know when to redraw or recompose.
- **Touch input** arrives as art-pixel coordinates: `onPress` / `onMove` / `onRelease` / `onCancel`. Behaviour depends on `tool` (and `selectMode` / floating state for Select).
- **Guards:** changing tool away from Select, changing layer/frame, and layer/frame operations all `commitFloating()` first. Undo while floating = cancel. Touching the canvas during playback just stops playback.
- **Playback:** `startPlaying()` pre-renders each frame's composite into `playImages` and loops with `delay(hold * 1000 / fps)`.
- **Onion skin:** `onionPrev` / `onionNext` are rebuilt on frame or structure changes only (other frames never change while you draw).
- **Autosave:** `changed()` schedules a save 2 s after the last edit. The screen also saves on `ON_STOP` and on back. If cells are floating mid-move, `save()` writes a copy with the lifted pixels put back, without changing what's on screen.

---

## 4. Rendering and gestures (`ui/editor/PixelCanvas.kt`)

Draw order: background → checkerboard (2×2 checks per art pixel when zoomed in) → onion skins (tinted,
semi-transparent) → current frame (`FilterQuality.None`) → grid (only when ≥ 6 screen px per art pixel,
stronger line every 8 pixels) → mirror axes (dashed pink) → center guides (cyan) → canvas border →
floating cells or selection outline ("marching ants").

`Viewport` holds `scale` (screen px per art px) and `offset`. Gestures use `awaitEachGesture`:
one finger draws, two fingers zoom/pan, and a second finger arriving mid-stroke **cancels** the stroke.

---

## 5. Compose pitfalls in this codebase

1. **Read a tick to observe model changes.** For example `vm.structureTick` in `LayersSheet` and `Timeline`, or `vm.renderTick` inside `Canvas` draw blocks (reading it in the draw phase redraws without recomposing).
2. **Lazy lists over in-place-mutated lists.** `project.frames` is the same `MutableList` instance forever. Under Compose's strong skipping, a `LazyRow { items(frames.size) }` lambda will **not** be re-run when a frame is added. Read the state (`vm.frameCount`, `vm.structureTick`) **inside** the lazy content block, as `Timeline.kt` does. This was a real bug (new frames didn't appear in the timeline).
3. Palettes are immutable `data class`es held in `mutableStateOf`, so palette UIs update normally.

---

## 6. Persistence (`data/`)

All files are in the app's private storage (`context.filesDir`). `allowBackup` is off.

| What | Where |
|---|---|
| Project | `files/projects/<uuid>.pxg` |
| Gallery thumbnail (frame 1, 1×) | `files/projects/<uuid>.png` |
| Palettes | `files/palettes.bin` |
| Active palette id | SharedPreferences `pixelgo`, key `active_palette` |

Writes go through a **single background thread** in each repository. Each write goes to a `.tmp` file first and is then renamed over the real file. `ProjectRepository.dispatcher` runs gallery reads on that same thread, so a read always sees the latest save.

### `.pxg` format (big-endian, `DataOutputStream`)

```
Header (uncompressed, readable without inflating — used by the gallery):
  int    magic = 0x5058474F ("PXGO")
  int    version            (1 or 2; current writer = 2)
  UTF    name
  int    width, height      (1..256)
  long   modified           (epoch ms)
  int    frameCount         (v2 only; v1 = 1 frame)

Body (java.util.zip Deflater stream):
  v2: int activeLayer, long nextLayerId, int fps, int currentFrame,
      frameCount × int hold,
      int layerCount,
      layerCount × { long id, UTF name, boolean visible, frameCount × int[width*height] pixels }
  v1: int activeLayer, long nextLayerId, int layerCount,
      layerCount × { long id, UTF name, boolean visible, int[width*height] pixels }
```

When changing the format: bump `VERSION`, keep reading every older version, and add a round-trip test
plus a hand-built old-version test (see `AnimationTest.readsOldSingleFrameFiles`).

### Palette file
`int magic = 0x50414C31 ("PAL1")`, `int count`, then per palette `UTF id, UTF name, int n, n × int color`.

---

## 7. Export (`data/PngExporter.kt`, `data/GifEncoder.kt`)

- Every export is a list of `ExportFile(fileName, mime, write)`. Builders: `pngFile`, `sheetFile`, `gifFile`.
- Pixels are **snapshotted on the main thread** (in `ExportDialog.files()`), then encoded and written on `Dispatchers.IO`.
- Scaling is nearest-neighbour, done row by row (`Raster.scaledRow`) into the output `Bitmap`, so a 4096² export doesn't need an extra 64 MB array.
- **Save:** Android 10+ uses MediaStore (`Pictures/PixelGo`, no permission needed). Android 8–9 writes the file directly, which needs `WRITE_EXTERNAL_STORAGE` (declared with `maxSdkVersion=28`, requested at runtime). One file goes straight into `Pictures/PixelGo`. Several files get their own sub-folder `PixelGo/<project>_<kind>_<timestamp>/`.
- **Share:** files are written to `cache/exports/` and shared through `FileProvider` (`${applicationId}.fileprovider`, see `res/xml/file_paths.xml`).
- **Sprite sheet:** frames in one row if the total width ≤ 4096 px, otherwise a square-ish grid (`sheetGrid`). Exports are refused above 8192 px per side.
- **GIF** (`GifEncoder`): GIF89a with one global palette (≤ 255 colors by popularity, the rest mapped to nearest) + 1 transparent index (alpha < 128), disposal "restore to background", NETSCAPE loop forever, variable-width LZW written in 255-byte sub-blocks. Delay per frame = `hold × 100 / fps` hundredths of a second (minimum 2).

---

## 8. Testing

- `./gradlew testDebugUnitTest` runs `EngineTest` (drawing, symmetry, fill, layers, selection, serialization) and `AnimationTest` (frames, holds, merge across frames, v1/v2 files, GIF validity via `javax.imageio`, sprite-sheet grid).
- Engine and serializers are plain Kotlin, so new logic should get a JVM test.
- **Manual device checks** before a release: draw with both mirrors, pinch-zoom mid-stroke (no stray pixels), layer reorder/merge/undo, select → move → ✓ / ✕, add/duplicate frames + onion skin + play, every export type (pull the files with `adb pull /sdcard/Pictures/PixelGo/...` and inspect them).
- The gallery sorts projects by **last modified**. When automating taps with `adb`, confirm which project is open before drawing.
