# CLAUDE.md

Working notes for AI-assisted development on PixelGo. Read `README.md` (features, product decisions,
status) and `docs/ARCHITECTURE.md` (how it works) before making changes.

## Commands

```bash
./gradlew testDebugUnitTest    # JVM unit tests (fast, no device)
./gradlew assembleDebug        # build debug APK
./gradlew installDebug         # install on the connected device
adb logcat -b crash            # check for crashes after launching
```

Needs `ANDROID_HOME` or `local.properties` (`sdk.dir=...`). `local.properties` is git-ignored.

## Rules

- **Local-only app:** never add the `INTERNET` permission, accounts, analytics or cloud features.
- **No brushes:** tools place or erase exactly one color per pixel.
- Every project mutation goes through `EditorEngine` and pushes an undoable `Edit`. Add a JVM test (do → undo → redo) for any new edit.
- Keep `editor/` and `data/Serializers.kt` / `data/GifEncoder.kt` free of Android imports so they stay unit-testable.
- Model objects are mutated in place. UI must read `vm.renderTick` / `vm.structureTick` to observe changes, and lazy lists must read that state **inside** their content block (see ARCHITECTURE §5).
- Every layer must have one cel per frame. Use `FrameOps` / `project.newLayer()`.
- File format changes: bump `ProjectSerializer.VERSION`, keep reading old versions, add tests.
- Product decisions in the README were made by the owner. Ask before changing them.

## Testing on a real phone

The owner may be using the test phone at the same time. Before sending `adb shell input` taps, check
`adb shell dumpsys window | grep mCurrentFocus` and take a screenshot. The gallery sorts by last-modified,
so confirm the project name/size on screen before drawing. Never edit the owner's own projects for
testing. Create a clearly named test project and clean up afterwards.
