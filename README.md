# PixelGo

A pixel-art editor and animator for Android, built with Kotlin and Jetpack Compose.

PixelGo is **local-only**: no accounts, no cloud, no network access (the app does not request the
`INTERNET` permission). Projects and palettes live in the app's private storage on the device, and
exports are written to the phone's `Pictures/PixelGo` folder.

---

## Features

| Area | What it does |
|---|---|
| **Canvas** | Any size from 1×1 to 256×256. Pinch to zoom, two-finger pan, pixel grid toggle, optional vertical/horizontal center guide lines, "Fit to screen". |
| **Drawing** | Pencil, eraser, flood fill, eyedropper. One pixel = one color (no brushes, by design). Strokes are interpolated so fast swipes leave no gaps. |
| **Symmetry** | Mirror left↔right and/or top↕bottom (applies to pencil, eraser and fill). Mirror axes are shown as dashed pink lines. |
| **Transparency** | Full alpha everywhere. A checkerboard shows transparent areas. Colors can be partly transparent (alpha slider / `#AARRGGBB` hex). |
| **Layers** | Add, duplicate, delete, rename, show/hide, reorder (drag the ≡ handle or use the arrows), merge down (asks for confirmation). |
| **Select** | Box or paint-brush selection. Copy, cut, move, paste, delete, select all/none. Moved/pasted cells float above the canvas: drag them, then ✓ to drop or ✕ to cancel. The clipboard works across projects until the app closes. |
| **Animation** | Frames share the same layers. Timeline strip with thumbnails, add blank / duplicate frame, per-frame hold (×1–×4), reorder/delete via long-press, playback preview, onion skin (previous frame red, next frame blue), speed 1–30 fps. |
| **Palettes** | Built-in "Default" palette plus unlimited custom palettes: create, rename, duplicate, delete, add/edit/reorder/remove colors. Color picker with HSV square, hue bar, alpha bar, hex entry and recent colors. |
| **Undo / redo** | Every edit (pixels, layers, frames, holds, selection cuts/moves) is undoable, up to 100 steps per editing session. |
| **Saving** | Autosaves 2 s after the last edit, when leaving the editor, and when the app goes to the background. |
| **Export** | Transparent PNG at 1×/2×/4×/8×/16× (nearest-neighbour, stays crisp): single image, each layer as its own PNG, animated GIF, sprite sheet PNG, or each frame as its own PNG. Save to `Pictures/PixelGo` or share. |

---

## Getting started

### Requirements

- **Android Studio** recent enough for **Android Gradle Plugin 9.4** (or just the command line with **JDK 17+**; development used Temurin JDK 21).
- **Android SDK Platform 37** (the project compiles against SDK 37 and targets 36). Android Studio's SDK Manager will offer to install anything missing.
- A device or emulator running **Android 8.0 (API 26) or newer**.

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

On Windows use `gradlew.bat` instead of `./gradlew`.

There is **no release signing configuration yet**. Only debug builds are set up.

---

## Project layout

```
app/src/main/java/com/pixelgo/
├── MainActivity.kt            Navigation: gallery → editor → palettes
├── PixelGoApp.kt              Application class; holds the two repositories
├── model/Model.kt             PixelProject, Layer (one pixel buffer per frame), Frame, Palette, limits
├── editor/                    Pure Kotlin (no Android imports), fully unit-testable
│   ├── EditorEngine.kt        Every edit goes through here: strokes, fill, selection, layers, frames, undo
│   ├── History.kt             Undo/redo stack and all reversible Edit types
│   ├── Selection.kt           Clip, Floating, selection outline math
│   ├── Raster.kt              Bresenham lines, mirroring, flood fill, nearest-neighbour scaling
│   └── ColorMath.kt           Alpha blending, layer compositing, hex parsing
├── data/
│   ├── Serializers.kt         .pxg project format (v1 + v2) and palette file format
│   ├── ProjectRepository.kt   Load/save/list/rename/duplicate/delete projects + gallery thumbnails
│   ├── PaletteRepository.kt   Palettes (Compose state) + persistence
│   ├── PngExporter.kt         PNG / sprite sheet / GIF file builders; save to Pictures or share
│   └── GifEncoder.kt          Self-contained animated GIF89a encoder (palette + LZW)
└── ui/
    ├── gallery/               Project grid, new-project dialog
    ├── editor/                Editor screen, canvas, view model, layers sheet, timeline,
    │                          selection bar, palette strip, export dialog, clipboard
    ├── palettes/              Palette manager
    ├── common/                Color picker, swatches, checkerboard, dialogs, eraser icon
    └── theme/                 Dark Material 3 theme
app/src/test/java/com/pixelgo/ EngineTest.kt, AnimationTest.kt (38 tests)
docs/ARCHITECTURE.md           How it fits together, file format, gotchas. Read this before changing code.
```

The only third-party libraries are AndroidX / Jetpack Compose (see `gradle/libs.versions.toml`).

---

## Product decisions

These were chosen deliberately by the project owner. Keep them unless the owner says otherwise.

- **Local-only.** No accounts, no cloud sync, no analytics, no network permission.
- **Place or erase a color only.** No brush shapes, sizes or textures.
- **Alpha matters.** Exports must keep transparent backgrounds. Painting *replaces* a pixel (including its alpha); layers blend with normal "source-over" alpha.
- **Canvas limit 256×256**, with presets 16/32/64/128/256.
- **Export scaling** is a whole-number multiple (1×–16×) using nearest-neighbour, so pixels stay sharp.
- **Mirror symmetry** "horizontal" means what happens on the left happens on the right (and vertical = top/bottom).
- **Merging layers asks for confirmation** and can be undone, including from inside the layers sheet.
- **Selection:** box or paint mode. A paint stroke that *starts* on a selected cell removes cells instead of adding them. Pasted/moved cells float until you tap ✓ or ✕. The clipboard is shared between projects.
- **Animation:** all frames share the same layers. Timing = one fps for the whole animation plus an optional per-frame hold. Helpers: onion skin, playback, duplicate frame, reorder frames.
- **Export formats:** single PNG, PNG per layer, animated GIF, sprite sheet PNG, PNG per frame.

---

## Status

All features above are implemented. They were tested by hand on a Samsung Galaxy S25 Ultra, and
38 JVM unit tests cover the engine, undo/redo, file format round-trips (including reading old
v1 files) and GIF output (decoded back with `javax.imageio`). There are no instrumented/UI tests yet.

### Known limitations

- **Undo history is not saved** with the project. It is lost when you leave the editor or the app is closed.
- **Fast diagonal strokes can leave small "L" corners** (there is no "pixel-perfect" stroke mode).
- **GIF export:** GIF has no partial transparency, so pixels with alpha < 128 become fully transparent and the rest become fully opaque. If the animation uses more than 255 colors, the 255 most common are kept and the rest map to the nearest one. Frame timing is rounded to 1/100 s (8 fps → 130 ms).
- **"Each layer" export** uses the current frame only.
- **Frames** are reordered from the long-press menu (move left/right), not by dragging. **Layers** can be dragged, but other rows don't animate out of the way.
- **Selection** works on the active layer only, and mirror symmetry does not apply to selection or paste.
- **Gallery thumbnail** shows frame 1 of an animation.
- **Memory cap:** at most 32 layers, 128 frames, and 24 million stored pixels in total (layers × frames × width × height). The add buttons are disabled when a limit is reached, without an explanation.
- **No release build setup** (signing, R8/minify, store listing).

### Ideas for next steps

- Persist undo history, or at least warn before leaving with a long history.
- Pixel-perfect stroke option.
- Drag-to-reorder frames in the timeline.
- Selection: flip/rotate, mirror-aware paste.
- More tools: line, rectangle, ellipse, color replace.
- Import a PNG (or sprite sheet) as a layer or frames.
- Release signing + Play Store listing.
- UI tests (Compose testing) for the editor flows.

---

## More documentation

- **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)**: data model, editing engine, undo system, rendering, persistence format, export pipeline, and Compose pitfalls specific to this codebase.
- **[CLAUDE.md](CLAUDE.md)**: short working notes for AI-assisted development (also useful for humans).
