# CLAUDE.md

Working notes for AI-assisted development on PixelGo. Read `README.md` (features, product decisions,
status) and `docs/ARCHITECTURE.md` (how it works) before making changes.

## Commands

```bash
./gradlew testDebugUnitTest    # JVM unit tests (fast, no device)
./gradlew assembleDebug        # build debug APK
./gradlew installDebug         # install on the connected device
adb logcat -b crash            # check for crashes after launching
ffprobe -show_streams x.mp4    # inspect an exported video (ffmpeg is at C:\ffmpeg on the dev PC)

# On-device media tests (MediaDeviceTest). NEVER use connectedAndroidTest: it uninstalls the app
# afterwards, which deletes every project on the phone. Install and run by hand:
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.pixelgo.test/androidx.test.runner.AndroidJUnitRunner
adb logcat -d -s PixelGoTest     # measured timings
adb uninstall com.pixelgo.test   # the TEST package only; never uninstall com.pixelgo
```

Needs `ANDROID_HOME` or `local.properties` (`sdk.dir=...`). `local.properties` is git-ignored.

## Rules

- **Local-only app:** never add the `INTERNET` permission, accounts, analytics or cloud features. Microphone, notifications and foreground-service permissions are fine.
- **No brushes:** tools place or erase exactly one color per pixel.
- Every project change goes through `EditorEngine` and pushes an undoable `Edit`. New `Edit`s must report what they reference (`collectRefs` / `collectAssetRefs`), or saving will garbage-collect data undo still needs. Add a JVM test (do → undo → redo) for any new edit.
- **Pixels live in the `CelStore`**, never in model objects:
  - Call `store.edit(layer, frame)` before every write.
  - Bulk work goes through a `BulkJob` (writes with `put`/`copy` off the main thread).
  - Track frames by **uid**, not index.
- Keep `editor/`, `data/Serializers.kt`, `MetaSerializer`, `DiskCelStore`, `Migration`, `FrameClipboard`, `data/audio/PcmFiles.kt`, `GifEncoder` and `data/video/{VideoLayout,YuvFrame,MovPatcher}` free of Android imports so they stay unit-testable.
- Model objects are mutated in place. The UI must read `vm.renderTick` / `vm.structureTick` to see changes. Lazy lists and gesture handlers must read live state (see ARCHITECTURE §9).
- **Saving:** snapshot on the UI thread, write on the repository's IO thread. Never touch live editor objects from IO threads. Background readers use `AnimationSnapshot`.
- **File format:** `project.meta` uses tagged sections; add new data as a new tag. Incompatible changes bump the version, keep old readers working, and need tests (migration test included).
- **Video:** keep scales and offsets even and colors BT.709 limited range (there are tests). MOV = MP4 + patched `ftyp`.
- **Sound timing:**
  - Exported sound stays in sync through `AacPriming`. Never shift encoder *input* timestamps; only the muxer's.
  - `AudioDecoder` must not trim `encoder-delay` itself: the codec already does.
  - Recordings are lined up by the count-in ticks the mic hears (`CountIn`), not by audio timestamps, which were ~0.1 s off on the test phone.
  - Re-run `MediaDeviceTest` after touching any media code.
- Product decisions in the README were made by the owner. Ask before changing them.

## Testing on a real phone

The owner uses the test phone at the same time, and its screen can show private messages.
- Use a check that **refuses to capture unless `com.pixelgo/com.pixelgo.MainActivity` is the focused window**, and re-check right before *every* screenshot (the notification shade can be pulled down mid-test).
- If anything else is on screen, stop and tell the owner. Delete any accidental capture without describing it.
- Never launch the app over another app the owner is using. Installing is fine; let them open it.
- The gallery sorts by last-modified, so confirm the project name and size on screen before drawing. Never edit the owner's projects: create a clearly named test project ("PixelGo test") and clean up afterwards.
- Back up `files/` (`adb exec-out run-as com.pixelgo tar cf - files`) before installing a build that changes the storage format.
- `MediaDeviceTest` never shows anything on screen, so it can run while the owner uses another app. It does restart PixelGo's process, so don't run it while PixelGo itself is in front.
- Notification banners can drop over the top of the screen without taking focus, so crop the top ~700 px of every screenshot.
- PixelGo's dropdown menus are separate windows titled "Pop-Up". Check that they belong to `com.pixelgo` (via `dumpsys window windows`), not just the title.
- Tap by label (`uiautomator dump`, filtered on the phone), not by remembered position. Rows move when tracks are added or deleted. **Before anything destructive, confirm on screen which item the menu belongs to.**
- Never copy microphone recordings off the phone; analyze them on the phone and print only timings.
