# PixelGo design system ("Light table")

How PixelGo looks and behaves, and the rules for new screens. The tokens live in
`ui/theme/Theme.kt` (object `Pg` + the Material theme) and the shared components in
`ui/common/Components.kt`. Build from those; don't style Material components one by one.

The direction came from a design audit (November 2026) that the owner approved. All four phases
are built: foundation, editor shell, flows (export, gallery, color picker, palettes, sound mode)
and polish (haptics, motion, TalkBack, icon).

## Principles

1. **The art is the brightest thing on screen.** Graphite surfaces, and a true neutral grey
   around the canvas so colors read correctly.
2. **Yellow means "this one".** The current tool, frame, swatch or selection, and the one main
   action on a surface. Nothing else is yellow: not Cancel, not sliders, not text buttons.
3. **One kind of control, one look.** Tools (one choice) are filled yellow when current.
   Toggles (on/off) get a yellow tint when on. Panel buttons and actions are neutral.
4. **Visible first, gestures second.** Every long-press has a visible equivalent (a ⋮ button or
   a More menu). Long-press stays as the shortcut.
5. **Pixel-true geometry.** 4dp grid, small radii, tabular numbers for time and sizes.
6. **Both orientations are first-class.** Sideways, the tools and colors move to rails.

## Tokens (`Pg`)

| Token | Value | Use |
|---|---|---|
| `Background` | #141414 | Behind everything, the timeline well |
| `Surface1` | #1C1C1C | Bars, the tool dock, rails |
| `Surface2` | #242424 | Sheets, cards, dialogs, context bars |
| `Surface3` | #2E2E2E | Inputs, secondary buttons, menus, frame cells |
| `Canvas` | #2A2A2A | Around the art |
| `Outline` | #383838 | Dividers, 1dp strokes |
| `Text1` / `Text2` / `Text3` | #F0F0F0 / #A6A6A6 / #666666 | Text and icons / secondary / disabled only |
| `Accent` / `OnAccent` | #FFD166 / #1A1A1A | "This one", and the main action |
| `AccentWeak` | accent at 16% | Selection fills, toggles that are on |
| `Live` | #FF4D4D | The playhead, recording |
| `Danger` | #FF7A73 | Destructive actions |
| `Success` | #5BD68A | Done, levels |
| `Tracks` | 6 colors | Sound tracks, in order |

The Material color scheme is mapped onto these, so Material's own defaults land right: sheets
and dialogs on `Surface2`, menus on `Surface3`, selected chips in the accent at 16%, no tinting
of raised surfaces.

**Type** (Roboto, the system font): Title 20/26 Medium (screens, sheets, dialogs) · Heading 15/20
Medium (`titleMedium`, sections) · Body 14/20 · Label 12/16 Medium (anything tappable in bars) ·
`Pg.Data` 13/16 Medium tabular (time, sizes, counts) · `Pg.Mark` 11/14 tabular (timeline marks
only; nothing is smaller).

**Spacing and sizes:** 4, 8, 12, 16, 24, 32 dp. Top bar 56 (48 sideways), icon bars 48, labelled
action bars 52, tool dock 64, color row 52. Every tappable thing has a 48dp hit area. Icons 24
(20–22 in dense bars). Swatches 32, the current color 40.

**Radius:** 2 art containers (frames, clips; drawn), 6 controls (buttons, chips, swatches,
inputs), 12 cards and menus, 16 sheets and dialogs. **Elevation:** flat; tone and 1dp outlines
separate surfaces; shadows only on things that float (menus, dragged items, FAB, snackbars).

## Components (`ui/common/Components.kt`)

| Component | Use |
|---|---|
| `PrimaryButton` | The one main action of a surface (filled yellow) |
| `SecondaryButton` | Other actions beside it (grey tone) |
| `PgTextButton` | Cancel and quiet actions (neutral; `destructive` = red) |
| `DangerButton` | Confirming something that can't be undone |
| `PgIconButton` | 48dp icon button; with `checked` it's a toggle (yellow tint when on) |
| `RoundIconButton` | Play / stop |
| `ToolButton` | A drawing tool (one choice; filled yellow when current) |
| `DockButton` | Non-tool dock buttons (Mirror, Layers); `on` for a state that's switched on |
| `ActionBar` + `BarAction` + `Choice` | Context bars: close, what's selected, up to 4 actions + More (grouped) |
| `Segmented` | One of a few modes (Box/Paint, Move/Select/Volume) |
| `PgSheet` | Bottom sheets: title, subtitle, header actions, content, a footer that stays visible |
| `SliderRow` / `PgSlider` | Labelled value with the number shown; no tick dots; optional − / + |
| `SwitchRow` | A setting that's on or off (the whole row toggles) |
| `SectionLabel`, `Hint` | Section headings; one-line explanations |
| `PgSnackbarHost` | Snackbars (with Undo) |
| `ColorSwatch` | A color with a checkerboard under it; the current one gets a yellow ring |
| `ColorPickerSheet` | Picking a color in a lightly dimmed sheet (add to palette, delete) |
| `mirrorIcon`, `OnionSkinIcon`, `EraserIcon` | Icons Material doesn't have |

## Interaction rules

- **Sheets edit what's on screen; dialogs only confirm or name.**
- **One primary action per surface**, bottom-right.
- **Undoable means no question.** Deleting frames, tracks, layers, clips and sound acts at once
  and shows "Deleted … · Undo" (`EditorViewModel.undoable`). The offer disappears as soon as
  another edit becomes the latest step. Permanent actions (deleting a project or palette) still
  confirm, with a red button. Merging layers keeps its confirmation (an owner decision).
- **Context bars show five actions at most** (More included); the rest go into More, grouped.
- **Every gesture has a visible equivalent.**

## Editor layout

- **Top bar:** back; project name with the layer and frame under it; Undo, Redo, Export; ⋮ with
  View (pixel grid, guides, fit to screen), Animation settings, Rename, Manage palettes.
- **Portrait:** canvas (the Undo snackbar floats at its bottom edge), the selection bar when the
  Select tool is on, the timeline (a handle on top switches compact / full; drag or tap), the
  color row (current color, swatches, palette menu, add), the tool dock (five tools, Mirror,
  Layers).
- **Landscape:** tools in a left rail, colors in a right rail, timeline along the bottom; Mirror
  and Layers move into the top bar.
- **Timeline header:** play, time, then onion skin, select frames, add, duplicate, sound mode.
  Record and import live in sound mode and the track menu; animation settings in ⋮.

## Flows (phase 3)

- **Export** is a sheet (`ExportSheet`): a preview of the current frame at the output's real
  shape (checkerboard for images, background and padding for video, the grid for a sprite
  sheet), six format tiles (Image, Layers, GIF, Sprite sheet, Frames, Video; MP4 or MOV is chosen
  inside Video), only that format's options, a one-line summary (pixels, frames, length, size)
  and Save (or Export video) with Share beside it.
- **Gallery:** the art fills each tile on a neutral ground (whole-pixel scaling, a quiet
  checkerboard), with the size, frame count and "Edited …". Each card has a visible ⋮. New
  projects are named "Sprite 1", "Sprite 2"…; the new-project sheet has size tiles and any
  width and height. With no projects, an empty state with one New project button.
- **Color picker** is a sheet (`ColorPickerSheet`) with a light dim, so the art stays readable
  above it: old and new color side by side, recent colors, "Add to palette" (in the editor) and
  a delete button (when editing a palette color).
- **Palettes:** tap a color to edit it; long-press and drag to move it (TalkBack: Move left /
  Move right actions).
- **Sound mode:** track names sit in a fixed header column (color stripe, name, muted state), so
  they never cover a waveform; tap one for its menu. Rows share the free height and are never
  shorter than the chosen track height; pinch and "Taller tracks" start from the height they
  really have.

## Polish (phase 4)

- **Haptics:** a light tick when the tool, a segmented choice or a dock button changes, on/off
  ticks for toggles, and a tick when a dragged clip snaps to a frame.
- **Motion:** the editor's bottom panel animates its height (180 ms) when the timeline grows or
  bars appear. Compose follows the system's animation scale, so "remove animations" turns it off.
- **TalkBack:** the timeline describes itself ("Timeline, frame 3 of 7, 2 sound tracks") and
  offers Previous frame / Next frame; swatches, tools, toggles and track headers have labels and
  states.
- **Icon:** a stepped pixel play arrow in yellow on graphite (the notification icon's shape),
  with a monochrome version for themed icons.

## Ideas for later

- A light surround option for the canvas (for dark art).
- An export preview that plays the animation.
- Per-frame semantics on the timeline (each frame as its own TalkBack item).
