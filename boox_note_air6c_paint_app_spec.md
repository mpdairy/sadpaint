# BOOX Note Air6 C — Custom Low-Latency Painting App

## Goal

Build a very simple painting/drawing app for the **BOOX Note Air6 C** that is optimized specifically for E Ink and stylus use.

The app should feel more like a small physical paint palette than a general-purpose art program.

The core goals are:

- very low pen latency
- pressure-sensitive stroke width
- opaque color only
- a small user-defined palette of colors
- fast one-tap color switching
- no transparency accumulation when strokes overlap
- good behavior at very high zoom levels
- minimal UI
- rendering tuned for the BOOX E Ink display rather than for a normal LCD/OLED Android device

The main motivation is that BOOX Notes has usable pen latency, but its color handling is limited. In particular, translucent marker colors look attractive while drawing but often settle differently after a full E Ink refresh, and overlapping strokes become darker because alpha accumulates.

This app should avoid that entire model.

---

## Desired Drawing Model

### Opaque paint only

All brush output should be fully opaque.

For example, painting blue over the same blue ten times should still produce the same blue.

Conceptually:

```text
destination_pixel = brush_color
```

not:

```text
destination_pixel = alpha_blend(destination_pixel, brush_color)
```

This eliminates marker-style overlap darkening.

There is no need for watercolor, marker transparency, glazing, or other alpha-based painting initially.

---

## Palette

The user should have a small palette of quickly accessible colors.

Suggested initial design:

- 8–16 large palette slots
- always visible along one edge of the screen
- tap a slot to select that color immediately
- long-press a slot to edit the color
- current color clearly indicated
- no modal color picker during normal drawing

The app should support multiple shades from the same hue family.

For example:

```text
pale blue
medium blue
dark blue
blue-gray
cyan-blue
```

There should be no artificial restriction like "one BOOX blue."

Internally, palette entries should be ordinary RGB colors.

For example:

```text
#C7D8E8
#6F9EC5
#315F88
#566B78
```

Exact values should eventually be calibrated for the physical Note Air6 C display.

---

## Color Calibration

The BOOX Note Air6 C uses a Kaleido color E Ink display.

The physically displayed color may differ substantially from the nominal RGB value, especially after a clean/full refresh.

Therefore, the palette should ideally be calibrated on the actual device.

A useful calibration mode would display a large grid of candidate colors.

Example:

```text
Blue family:
B1 B2 B3 B4 B5
B6 B7 B8 B9 B10

Red family:
R1 R2 R3 R4 R5
...
```

Then:

1. render candidate colors
2. force or wait for a clean E Ink refresh
3. visually inspect the settled colors
4. choose the ones that actually look good
5. save those RGB values into the active palette

The important design principle is:

> Tune colors for their final settled appearance on the E Ink panel, not for how their RGB values look on a normal monitor.

---

## Stylus Behavior

The BOOX Note Air6 C uses a passive Wacom EMR stylus.

Required stylus behavior:

- pressure controls stroke width
- pressure curve should be adjustable
- extremely thin strokes should be possible
- very thick strokes should be possible
- stylus draws
- fingers pan/zoom rather than paint

The user often works at approximately **700% zoom** because this makes pressure variation much more useful for painting and drawing.

Therefore, the app must behave well at extreme zoom levels.

Pressure mapping should probably be configurable rather than strictly linear.

Example:

```text
width = min_width + pow(normalized_pressure, gamma) * width_range
```

Useful settings:

```text
min_width
max_width
pressure_gamma
pressure_deadzone
```

---

## BOOX Low-Latency Pen Path

BOOX provides APIs intended for low-latency handwriting.

Historically these include classes/interfaces such as:

```text
TouchHelper
openRawDrawing()
BrushRender
```

The basic strategy should be:

```text
BOOX raw stylus input
        |
        v
x / y / pressure samples
        |
        +--> immediate low-latency visual feedback
        |
        v
painting engine
        |
        v
update only dirty image tiles / rectangles
```

The implementation should investigate BOOX's current SDK and the current Note Air6 C firmware rather than assuming old SDK documentation is complete.

The first technical experiment should be extremely small.

---

## First Prototype / Feasibility Test

Before building the app, make a tiny test APK.

It should contain:

- blank white canvas
- one opaque color
- pressure-sensitive brush
- raw BOOX stylus input
- live display of:
  - pressure value
  - event/sample rate if useful
  - optionally approximate drawing latency
- minimal dirty-region E Ink updates

Test whether the stroke feels comparable to the built-in BOOX Notes app.

This experiment should answer the biggest unknown:

> Can a third-party app get a sufficiently fast pen/display path on the Note Air6 C?

Do not build a complicated UI before answering this.

---

## Important Color Experiment

The next experiment should test **live colored ink while the pen is still moving**.

Try several approaches.

### Case A — native fast path supports colored strokes

Best outcome.

Use the BOOX native/native-assisted low-latency drawing path with the selected opaque RGB color.

If this works cleanly, use it.

### Case B — native fast path is monochrome, but partial color refresh is fast enough

Use raw stylus coordinates and pressure.

Render the actual RGB brush into the app's backing bitmap and update only the dirty rectangle around the current stroke segment.

Because the Note Air6 C has BOOX Super Refresh / BSR hardware, this may still be responsive enough.

### Case C — only monochrome preview can be truly immediate

Fallback:

```text
pen down:
    draw temporary fast black/gray preview

pen up:
    replace preview with final opaque color
    refresh dirty region
```

This is acceptable as a fallback but not the preferred experience.

The purpose of the prototype is to determine which of these is possible on current firmware.

---

## Painting Engine

The permanent drawing should probably be implemented as a raster canvas.

A tiled backing store is preferable to one huge bitmap.

Suggested tile size:

```text
128x128
or
256x256
```

Advantages:

- efficient dirty-region updates
- easier extreme zoom
- lower redraw cost
- easier partial persistence
- easier undo later
- avoids repainting the whole image after every stroke

Conceptually:

```text
Canvas
  ├── tile(0,0)
  ├── tile(1,0)
  ├── tile(2,0)
  ├── ...
```

A brush stroke only modifies the tiles it intersects.

---

## Rendering Pipeline

Suggested pipeline:

```text
Stylus event
    |
    v
screen coordinate
    |
    v
convert to canvas coordinate using current zoom/pan
    |
    v
read pressure
    |
    v
compute brush radius
    |
    v
rasterize opaque brush into affected tiles
    |
    v
compute dirty screen rectangle
    |
    v
request minimal E Ink update
```

Do not invalidate or redraw the entire view for each stylus sample.

Dirty rectangles are important.

---

## Brush

The first brush can be extremely simple.

A round hard-edged or slightly antialiased pressure-sensitive brush is enough.

Potential first implementation:

```text
radius = pressure_to_radius(pressure)
draw filled circle / stamp at each interpolated point
```

Interpolate between incoming samples so fast pen motion does not create gaps.

Possible later options:

- hard round
- soft round
- square/block
- pencil-like texture

But these are not required for the first working version.

---

## Zoom and Pan

Required:

- pinch-to-zoom
- finger drag to pan
- stylus should never accidentally trigger pan
- zoom should support at least roughly:
  - 25%
  - 50%
  - 100%
  - 200%
  - 400%
  - 700%
  - preferably much higher

At high zoom, brush width should remain expressed in **canvas coordinates**, not screen pixels.

That way zooming in reveals finer control instead of changing the physical brush size unpredictably.

---

## UI

Keep the interface intentionally minimal.

Possible layout:

```text
+------------------------------------------------------+
|                                                      |
|                                                      |
|                    CANVAS                            |
|                                                      |
|                                                      |
|                                                      |
|                                                [B1]  |
|                                                [B2]  |
|                                                [G1]  |
|                                                [R1]  |
|                                                [Y1]  |
|                                                [K ]  |
+------------------------------------------------------+
```

Palette can be on either side.

Optional small controls:

```text
undo
redo
brush size / pressure curve
save
new canvas
```

Avoid large toolbars and nested menus.

---

## Grayscale Mode

Even though the main reason for this app is better color handling, grayscale should remain a first-class mode.

A default grayscale palette could include:

```text
white
very light gray
light gray
medium gray
dark gray
black
```

This matches the current workflow where several custom BOOX pens are configured to different grayscale values.

---

## Possible Rust Architecture

Rust is reasonable for the raster engine but is not required for the first prototype.

Suggested architecture:

```text
Android/Kotlin layer
    |
    | BOOX SDK / stylus input
    | Android lifecycle
    | gestures
    | file picker
    | display integration
    v
JNI
    |
    v
Rust painting engine
    |
    | tile storage
    | brush rasterization
    | pressure mapping
    | coordinate transforms
    | dirty-region calculation
    | image encode/decode
```

Do not move code into Rust merely for ideological reasons.

First get the low-latency BOOX path working.

Then profile.

If ordinary Kotlin/Java raster code is already fast enough, keeping the implementation simpler may be better.

Rust becomes especially attractive if:

- brush rasterization becomes CPU-heavy
- large canvases are used
- custom blending is added
- image processing grows
- memory control becomes important

---

## Root / Kernel Access

The initial implementation should assume:

**NO root**
**NO custom kernel**
**NO firmware modification**

The BOOX SDK/raw drawing APIs should be investigated first.

Only consider deeper reverse engineering if the public or accessible firmware APIs cannot provide acceptable pen/display latency.

There is no reason to begin at kernel level.

---

## Saving Files

For an initial version, choose a simple format.

Possible approach:

```text
project directory:
    metadata.json
    tile_0_0.png
    tile_1_0.png
    tile_0_1.png
    ...
```

or a single custom archive containing tiles.

Export should support at least:

```text
PNG
```

Potential later formats:

```text
JPEG
WebP
PSD-like layered export
SVG only if vector tools are later added
```

The initial app does not need layers.

---

## Undo

Undo is optional for the first prototype.

If implemented, stroke-based undo is preferable.

For each stroke, record either:

- changed pixels/tiles before modification
- a compact command representation that can be replayed

Because E Ink encourages a simple, direct workflow, it may even be useful to allow a "destructive mode" with undo disabled.

---

## Performance Priorities

Order of importance:

1. pen latency
2. correct pressure response
3. stable opaque colors
4. fast color switching
5. minimal visual flashing
6. smooth high-zoom navigation
7. large-canvas performance
8. extra brushes/features

Do not sacrifice latency for fancy UI.

---

## E Ink-Specific Design Rules

This is not a normal Android painting app.

Avoid assumptions from LCD/OLED UI design.

Prefer:

- large simple controls
- few animations
- minimal full-screen redraws
- partial dirty-region updates
- deliberate clean refreshes when needed
- limited stable palette
- high-contrast UI
- very little translucent UI
- no unnecessary gradients

Ghosting should be treated as part of the rendering problem.

A manual "clean refresh" button may be useful.

The app may also choose to trigger a cleaner refresh:

- after a stroke
- every N strokes
- after zooming
- when idle
- when the user requests it

This should be configurable after testing.

---

## Key Questions To Answer On Real Hardware

These should be tested on the actual Note Air6 C.

### Pen path

- Can `openRawDrawing()` or its current equivalent be used?
- What stylus sample rate is received?
- Is pressure reliable?
- Is tilt available and useful?
- Does BOOX expose its ultra-low-latency native handwriting overlay?

### Color

- Can the fast/native renderer draw arbitrary RGB?
- Can it draw several shades of the same hue?
- Can opaque colors be displayed while the pen is down?
- What E Ink waveform/update mode does BOOX Notes use for colored ink?
- Can a third-party app request the same or similar update mode?
- How different does the stroke look before vs. after a clean refresh?

### Display updates

- What is the smallest practical dirty rectangle?
- How frequently can partial color updates occur before performance suffers?
- How severe is ghosting?
- When is a full/clean refresh actually necessary?

### Pressure / zoom

- Does high zoom preserve smooth pressure response?
- What pressure curve feels best?
- How thin a line can reliably be rendered?

---

## Suggested Development Sequence

### Phase 1 — raw pen test

Make a blank-canvas APK with raw BOOX pen input.

Success criterion:

> Drawing feels nearly as immediate as BOOX Notes.

### Phase 2 — pressure brush

Add a pressure-sensitive black opaque brush.

Success criterion:

> Very thin through very thick strokes work smoothly.

### Phase 3 — colored opaque brush

Add one arbitrary RGB color.

Test live color while the pen is moving.

Success criterion:

> Color does not accumulate/darken when painting over itself.

### Phase 4 — palette

Add 8–16 instantly selectable colors.

Add palette editing.

### Phase 5 — zoom/pan

Implement very large zoom values and finger navigation.

### Phase 6 — tiled canvas

Move permanent painting storage to tiles if not already done.

### Phase 7 — color calibration

Create a physical-screen palette calibration tool.

### Phase 8 — save/export

Persist projects and export PNG.

Only after all of this should more sophisticated brushes, layers, selections, etc. be considered.

---

## Minimal Version That Would Already Be Useful

A genuinely useful V1 could be only:

```text
- white canvas
- BOOX low-latency stylus input
- pressure-sensitive round brush
- opaque paint
- 10 user-defined color swatches
- pinch zoom
- finger pan
- undo
- save
- PNG export
```

That is enough.

The app should resist feature creep.

---

## Main Design Principle

The app is not trying to imitate Photoshop, Procreate, Krita, or BOOX Notes.

It is trying to create a very specific physical-feeling E Ink painting instrument:

> pick a color, press harder or softer, make a mark, zoom in enormously when desired, and never fight transparency, menus, or sluggish redraws.

The display limitations should shape the app rather than being hidden behind a conventional Android painting architecture.
