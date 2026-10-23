# Plan: palette builder, saved palettes, Manta-style paint screen

Handoff for the next agents. Written 2026-09-24 after a day of physical color
tests on the NoteAir6C. Read "What we learned" first: it rules out several
tempting designs.

**Status 2026-09-24 (0.9.0):** steps 1–3 done and on the device
(`PaletteLibrary`, `PaletteBuilderActivity`, `PicksGrid` extracted from Picks,
Manta-style `PaintActivity`, `Palette.MAX` = 24). Step 4 (physical gray-preview
test) is waiting on the user.

Build (the README command needs both env vars):

```sh
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ANDROID_HOME=/home/teddy/Android/Sdk \
./gradlew :app:assembleDebug :app:testDebugUnitTest
adb -s 9a91e053 install -r app/build/outputs/apk/debug/app-debug.apk
```

Package `dev.tilesmile.pentest`. Test screens launch directly, e.g.
`adb shell am start -n dev.tilesmile.pentest/dev.tilesmile.PicksActivity`.
Editing an app prefs file with adb: `am force-stop` the app first, or its
in-memory copy overwrites your edit.

## What we learned about the panel (physical, user-confirmed)

- **Use only the user's calibrated picks.** They are the colors that render
  solid on this screen. Stored in prefs `swatch-library-v1`, key `good`
  (hex string set), loaded by `CalibrateActivity.load()`. Currently 105 picks
  after the user pruned them. Display setting at calibration time: contrast 0,
  saturation at default, HD refresh.
- **Full-strength colors below about 40% HSV brightness all read as near-black.**
- **Pixel mixing (1 px ordered/Bayer dither) of a color with black works:** it
  keeps a visible tint deeper than blended darks do (`GradientActivity`,
  "mix 1px" rows). 2 px and 4 px cells look dotty.
- **Pixel mixing two different colors fails:** it goes grayish with a diagonal
  hatch (`CmykActivity`). The panel's color layer is coarser than its pixels,
  and our pattern interferes with the panel's own dithering. Every mixed color
  wheel (`WheelActivity` modes Mixed and Clean mix) looked ashen for this reason.
- **The panel's most vivid colors are pastels.** Even 00FFFF/FF00FF/FFFF00
  show as pale teal/pink/tan.
- **Decision:** no black mix-in for now. The palette is chosen from solid picks
  only. Black-dither shading stays a possible later experiment.
- **Fast pen preview:** the firmware draws the live stroke. The app sets only
  style, one color and width (`TouchHelper`), with no per-pixel control. The auto
  preview is now the visible survey gray closest in lightness
  (`PreviewColors.nearestGray`). All 9 surveyed grays were visible. Per-color
  overrides still win. **Not yet tried physically with real picks: that is the
  last step of this plan.**

## What exists now

- `PicksActivity`: grid of all picks, one column per hue family (12 OKLab 30°
  sectors, then warm/neutral/cool grays via `CalibrationColors.family`). Light
  to dark by default. Tap selects a swatch (frame + hex + **Delete** button,
  no confirm). Drag up/down reorders within a column. Order is saved as
  prefs `swatch-library-v1` key `order` (comma hex list).
  `PicksActivity.columns(context)` returns the ordered columns. Swatch height
  is capped at 1/32 of the view (the user prefers short swatches).
- `PaintActivity`: canvas plus a right-side gray panel (Undo, Redo, width −/+,
  vertical swatch column of `Palette` entries up to 16, Live fast/true toggle,
  Refresh, More…). Long-press a swatch opens an HSV/hex editor dialog with
  calibrated chips. Palette saved in prefs `paint-v1` key `palette`
  (`Palette.serialize`: `RRGGBB:auto|RRGGBB,...`) plus `selected`.
- Test screens (keep them, reachable from More…): `WheelActivity`,
  `GradientActivity`, `CmykActivity`, `CalibrateActivity`, `SweepActivity`.
  Only Calibrate and the older sheets are in the More… menu today. Add Picks
  and the new ones.

## Reference: the Manta app (`../supernote_tilesmile`)

Its layout: a **top bar** (files icon button 112dp × 48dp on the left, then the
shade picker and a selected-shade preview), and a **left tool rail** (112dp,
scrollable) of icon-only buttons. The selected tool has a 2dp black border, and
a small chevron at the right edge opens that tool's settings. Undo/Redo sit at
the bottom of the rail. See `supernote/PaintActivity.java` lines ~65–240
(`button()`, `ToolButton`, `rebuildTools`).

Icons are monochrome 24dp vector drawables in
`../supernote_tilesmile/app/src/main/res/drawable/`: `ic_brush, ic_undo,
ic_redo, ic_files, ic_open, ic_save, ic_new, ic_settings, ic_chevron,
ic_preset, ic_eraser, ic_pencil, ic_fill, ic_soften`. This app has **no res
directory yet**: create `app/src/main/res/drawable/` and copy what's used
(at least brush, undo, redo, files, save, open, settings, chevron). Only the
look is copied. Its pen/dither tech is Supernote-specific and out of scope.

## What to build

### 1. Palette builder screen (extend `PicksActivity` or a new `PaletteBuilderActivity`)

- The picks grid on top, as it is now (tap selects, drag reorders, Delete).
- **A palette row of boxes beneath the grid**, showing the palette being
  edited. With a pick selected, an **Add to palette** button appends it
  (ignore duplicates). Tap a box in the row to select it, then **Remove** or
  drag it left/right to reorder. Allow more than 16 if the row still fits,
  or wrap to two rows. Choose a sensible max and note it.
- Top bar: palette name (tap to rename), **Save**, **Save as new**, **Open**
  (list of saved palettes, with delete), **Use** (make active and return to
  painting), Done.
- Opening from the paint screen edits the active palette.

### 2. Saved palettes (model + storage)

- Named palettes, e.g. "Portrait" or "Zorn". Store in prefs `palettes-v1`: key
  `names` (ordered list), and `palette:<name>` → `Palette.serialize()` string.
  Keep `Palette` as the model and its format (it keeps preview overrides).
- `paint-v1` keeps the active palette as now, plus `activeName`. On first run,
  migrate the current palette into a saved palette named "Default".
- JVM tests: save/load/rename/delete round trip, migration, duplicate names.

### 3. Paint screen restyle, Manta look

- **Left icon rail:** Brush (the only tool for now; selected border and settings
  chevron that opens width −/+, the Live fast/true toggle and Refresh),
  Undo, Redo, and a Palette/files icon that opens the builder. Keep "More…" as
  a small icon (`ic_settings`) for the test screens.
- **Palette always visible:** a row of swatch boxes along the top bar, like the
  Manta's shade picker. Tap selects the color (bold frame); long-press still
  opens the per-color editor (hex/preview override). Also a quick palette
  switcher (the palette name in the top bar opens the saved list).
- Keep the canvas logic (`PaintSurface`) untouched. Only the chrome changes.
  Changing the rail width changes the canvas size; `PaintSurface` already
  crops on resize, but check the saved canvas PNG still loads.
- E-ink: after changing palette or tool UI, call the existing clean refresh
  sparingly. Avoid redraw churn while the pen is down.

### 4. Try the gray preview with real picks (with the user)

- Build a test palette from picks across the lightness range (pale pink,
  mid teal, dark navy, a gold, a neutral gray). Draw with fast/native
  preview on. Check that each preview gray is visible while drawing, that its
  lightness roughly matches the finished color, and that the finished color
  lands on pen-up. Record results in README.
- Known risk: very pale picks map to E0E0E0/F8F8F8, which may be hard to see
  on white. If the user finds that bad, bias the auto mapping one gray step
  darker for pale colors (in `PreviewColors.nearestGray`) rather than
  reintroducing hue previews.

## Order and done-ness

1 → 2 → 3 → 4. Steps 1–3 are verifiable by build, tests and screenshots
(`adb exec-out screencap -p`; note screenshots show app pixels, not how the
panel physically renders). Step 4 needs the user's eyes. Update README's
"Painting app" section at the end.
