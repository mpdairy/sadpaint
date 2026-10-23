# Verification — 2026-09-23–24

Device: connected BOOX NoteAir6C, Android 16 / API 36, arm64-v8a,
1860 × 2480 physical display.

## Passed

- `:app:assembleDebug`: debug APK built and installed successfully.
- `:app:testDebugUnitTest`: 6 tests, 0 failures, 0 errors.
- `:app:lintDebug`: 0 errors, 11 warnings (prototype UI and vendor-library warnings).
- Final merged manifest requests no Android permissions.
- Cold launch: activity opened successfully, no app crash observed.
- BOOX SDK: `isRawDrawingCreated() == true`, pressure maximum reported as 4096.
- Mode switching A → B → C → A: each BOOX raw session created successfully.
- Home and resume: BOOX raw session reopened successfully.
- Synthetic Android-control stylus stroke: blue line rendered; 29 samples
  over 787 ms, pressure 1.0. This verifies the software path only.

## Physical pen test — 2026-09-24

The user reports that drawing works and feels "super low latency" on the BOOX.
Device logs confirm 11 physical strokes in **A: Native color** (`mode=0`,
`raw=true`), with approximately 480–490 received samples per second. Per-stroke
peak pressure varies from 3164 to 3945 out of the reported maximum of 4096.
The first recorded stroke contains 812 samples over 1697 ms.

This confirms functioning BOOX raw input with pressure values, and acceptable
perceived pen latency on this device. The rate is callback throughput, not a
measurement of display latency or a claim about the digitizer's hardware rate.

The user initially reported that the original blue stayed approximately the
same while drawing and after lifting. Testing additional shades subsequently
showed that arbitrary RGB preview is not working with the current native path;
see the version 0.3 investigation below.

Further palette and live-bitmap results are recorded below. Still to check:
pressure-to-width feel and stable overlapping color after clean refresh. Follow the
[physical test steps](README.md#five-minute-physical-pen-test). No camera-based
end-to-end latency measurement has been made.

## Artifacts

- [Installable APK](artifacts/tilesmile-pen-test.apk)
- [Device check log](artifacts/device-check.txt)
- [Android control screenshot](artifacts/android-control.png)
- [Ready screen](artifacts/ready.png)
- [Unit-test report](app/build/reports/tests/testDebugUnitTest/index.html)
- [Lint report](app/build/reports/lint-results-debug.html)

## Blue palette update — version 0.2

Added five blue swatches and black, with a check mark and border indicating
the selected color. Each selection sets the same opaque RGB on the backing
brush and native preview (mode C intentionally keeps its black preview).

The updated APK built, passed lint with 0 errors, and was installed on the
NoteAir6C. Automated taps selected all six swatches; the raw session reopened
successfully for each requested color. No app crash was observed. The app is
left in native-color mode with the original blue selected. Physical rendering
of the additional shades remains for the user to compare.

- [Palette screenshot](artifacts/blue-palette.png)
- [Palette selection log](artifacts/palette-device-check.txt)
- [Screenshot of the previous drawing before update](artifacts/before-blue-palette.png)

SDK startup also probes unrelated firmware methods that Android refuses.
Those vendor log messages did not prevent session creation. The app uses the
official sample's compatibility library, limited to BOOX namespaces and its
View refresh methods; device security settings were not changed.

## Preview-color investigation — version 0.3

The user reports that pale blue and light blue appear white during pen-down,
and blue-gray appears black. All settle into their selected colors after lift.
This establishes a native-preview color mismatch for those shades, not a
failure of the final opaque backing image. Its precise firmware cause is still
unconfirmed; the original blue working does not establish arbitrary RGB support.

The user also reports white during pen-down in B, with color appearing after
lift. Inspection of the shipped SDK identified a bug in the application's B
setup: `TouchHelper.setRawDrawingEnabled(true)` internally also invokes
`setRawDrawingRenderEnabled(true)`. The app had disabled rendering **before**
enabling raw drawing, unintentionally turning the native preview back on.

Version 0.3 applies the native-render flag after enabling raw drawing and leaves
raw input enabled across B's pen-up events. Status and logs now expose the
native-render state. The subsequent physical test confirms correct live color,
with more perceived lag than the native preview (see below).

The corrected APK built and passed lint, then was installed. Device logs
confirm `rawInput=true` and `nativeRender=false` in B, including after color
selection and resume. Mode A still reports `nativeRender=true`. These checks
verify the configuration fix, not physical display timing.

- [Corrected mode-state log](artifacts/live-bitmap-fix-check.txt)
- [Pre-fix physical stroke log](artifacts/preview-color-failure.txt)

## Correct live color confirmed — version 0.3

After retesting B, the user reports that it is "a bit more sluggish" but
"looks exactly rite" while drawing. Logs confirm physical raw strokes in B
for all five blue shades and black, with `nativeRender=false` throughout the
recorded completed strokes. Callback throughput remains roughly 470–490
samples/second; this is not a measurement of visible latency.

The feasibility result is now: the current native preview feels very fast but
misrepresents some shades; raw-input-driven bitmap drawing displays the tested
colors correctly during pen-down, with additional perceived lag. The next
engineering step is to profile and reduce the bitmap path's delay. No claim
has been established that it can match native-preview latency.

- [Successful live-color stroke log](artifacts/live-color-confirmed.txt)

## Bitmap performance experiment — version 0.4

The app now starts in B with **Fast ink** on and **Live stats** off. Fast ink
waits for surface buffers on a dedicated thread, allowing input to continue,
and copies the newest available pixels after that wait. It reserves nearby
dirty space for incoming samples and retains any damage outside that space
for another frame. Turning Fast ink off restores the old 16 ms main-thread
schedule. All colors and the pressure curve are unchanged.

Six alternating, deterministic 961-sample replays were run on the NoteAir6C
at a requested 480 samples/second. Both schedules had live stats disabled.
The final comparison explicitly checked that the tablet stayed awake.
Ranges across three runs per schedule:

| App-side measurement | Old schedule | Fast ink |
| --- | --- | --- |
| Newest included sample age at submission, median | 11.49–29.70 ms | 5.00–5.07 ms |
| Newest included sample age at submission, p95 | 37.02–40.37 ms | 7.23–8.01 ms |
| Oldest pending change to submission, median | 26.08–45.15 ms | 43.74–48.74 ms |
| Submitted frames per roughly 2-second replay | 53–65 | 53–54 |

This improves sample freshness and removes buffer waits from input handling;
it does **not** establish lower physical pen-to-panel latency. Frame cadence
and oldest-change delay did not improve, so the on-device feel comparison is
still necessary. Surface submission also precedes compositor and E Ink work.
Freshness excludes frames whose reserved clip did not cover all pending damage.
The replay feeds application samples directly and does not measure the EMR.

Validation passed: six JVM brush tests, six full bitmap/reference comparisons
on the tablet, raw input ON/native preview OFF checks, large jumps beyond the
reserved dirty region, recoloring, and immediate pause/resume with pending ink.
Build and lint passed (0 lint errors, 12 warnings). A frame-synchronized
main-thread experiment was measured and rejected because it did not improve
the buffer waits.

- [Final replay and regression result](artifacts/perf-awake-run.txt)
- [Final comparison timings](artifacts/perf-awake-log.txt) (last replay process)
- [Pre-optimization screenshot](artifacts/before-performance-work.png)

## Native preview survey — version 0.5

The user reports that 0.4 feels about the same, despite the improved app-side
sample freshness. For opaque tile painting, an approximate live color may be
acceptable; the user found that mismatch harder to use with transparent markers.

Added a 63-request physical survey using mode A's native fountain preview. It
includes the original inks, saturated colors, grayscale, blue channel sweeps,
and a coarse RGB cube. Requested RGB and user-reported live color are stored
separately. No panel outcomes are inferred from SDK acceptance, final bitmap
pixels, synthetic replay, or screenshots. White and invisible are one category
because the test canvas is white. This is a coarse, single-device survey, not
a full gamut measurement or proof of exact RGB matching.

Physical observations of these new candidates remain pending. Existing user
reports establish that pale/light blue looked white and blue-gray looked black
in native preview; those reports are not automatically prefilled as fresh trials.

Validation passed on the connected NoteAir6C: all 63 requests retained native
input/rendering before and after synthetic pen-up and produced the exact
requested final bitmap pixel. Simulated strokes did not enable recording or
alter observations. Pause/resume and picker selection/restoration passed.
The APK and test APK built, six existing brush tests passed, and lint reported
zero errors. A delayed picker callback discovered during UI inspection was
fixed and the full 63-request check rerun successfully. Screenshots verify
layout only. CSV file-picker export and actual pen-driven observation entry
still need manual use.

- [On-device software checks](artifacts/preview-checks.txt)
- [Survey UI](artifacts/preview-survey-ui.png)

## Fixed finished color with selectable previews — version 0.6

The user clarified the intended experiment: keep the finished ink fixed and
try different native preview requests until one feels closest. The earlier
survey changed both colors together and therefore did not implement that
comparison. The initial answer buttons also used identical theme backgrounds.

The new screen separates finished and preview colors. Its colored swatches set
only the native SDK preview request; the raster continues using the selected
finished ink. Switching previews retains earlier strokes, and nothing advances
automatically. Favorites are stored per finished color and restored in this
comparison screen. Changing the finished color clears the comparison canvas.
The main drawing screen's existing modes are unaffected. Physical matching
quality still depends on the user's pen tests.

Build, lint (zero errors), and all six brush unit tests passed. On-device checks
passed for all 63 preview requests against a fixed finished ink: final pixels
kept the finished color, changing preview preserved previous strokes, selecting
a different finished target changed final pixels, and independent colors
survived pause/resume. Selection restoration and unchanged favorites passed.
The screenshot confirms the separate controls and colored swatches; it does
not measure the physical native overlay. Favorite saving has not yet been
exercised by a user in this version.

- [Fixed-final-color software checks](artifacts/preview-match-checks.txt)
- [Corrected comparison UI](artifacts/preview-match-ui.png)

## All preview swatches visible — version 0.6.1

Replaced the twelve-swatch pages with a nine-column, seven-row grid showing all
63 candidates. Removed paging buttons and the redundant preview dropdown.
Build, test-APK compilation, and lint passed. On-device layout inspection
confirmed every swatch is visible with drawing space below. Tapping the last
swatch selected preview #FFFF80 while finished ink stayed pale blue #C7D8E8;
the initial choice was then restored. The renderer was unchanged, so the full
63-color replay was not repeated for this layout change.

- [Complete palette layout](artifacts/all-preview-swatches-ui.png)
- [Last swatch selected](artifacts/all-swatches-last-selected.png)

## Native-only automation probe and visibility test — version 0.7

Tried the documented native start/add/finish stroke commands with black,
original blue, and pale blue, without sending points to the application raster.
The firmware methods existed, but start returned width zero for all three.
Screenshots taken before finish showed zero changed and zero nonwhite pixels
in the drawing area for all three requests. The backing bitmap stayed white.
This does not establish whether synthetic native ink appeared on the physical
panel. It cannot classify requests as visible or invisible, and no outcomes
were inferred from these blank readbacks.

Added **Preview visibility test**: one request at a time, black first, large
VISIBLE / INVISIBLE buttons, physical pen-stroke gate, saved results, automatic
advance to the next untested request, Previous, Skip, and Clear + refresh.
Results include timestamps and firmware fingerprint in separate private
preferences. Visibility is judged during pen-down on white; it neither proves
an exact color match nor distinguishes duplicate visible preview colors.

Build, lint, and six brush unit tests passed. On-device UI checks passed for
the physical-stroke gate, rejection of bitmap-only replay, both answers saving
and advancing, and requiring a fresh stroke for the next sample. Simulated
answers used isolated test preferences which were deleted; the user's real
observations were unchanged. The installed app is open on the black control.

- [Native-only probe results](artifacts/native-preview-probe.txt)
- [Native command logs](artifacts/native-probe-log.txt)
- [Visibility UI checks](artifacts/visibility-ui-checks.txt)
- [Visibility screen](artifacts/visibility-test-ui.png)

## Completed physical survey and automatic previews — version 0.7.1

The user completed all 63 samples. Saved records contain **46 visible** and
**17 invisible** requests. These do not establish 46 distinct preview colors.
Pale/light blue were both invisible; original blue, deep blue, blue-gray, and
black were visible. See [the result summary](PREVIEW_VISIBILITY_RESULTS.md)
and its CSV for all values and original metadata.

The optional matching palette now hides requests marked invisible while keeping
all six finished colors, including pale/light blue. The normal drawing screen
uses native mode A after the original blue is confirmed visible. Its five blue
finished inks share that previously observed blue preview; black uses black.
Saved favorites override defaults unless marked invisible. This avoids any
required manual matching survey for the prototype, while preserving exact final
bitmap colors. It is not a full-gamut nearest-color model.

Build, lint, six brush unit tests, and on-device checks for all 46 remaining
requests passed. Checks verified filtering, retained finished inks, exact final
pixels, preserved strokes, pause/resume, and unchanged visibility results and
favorites. Actual main-screen palette taps confirmed the automatic SDK color
configuration for all six inks. The app is left in native drawing mode with
pale blue finished ink and original-blue preview.

- [Filtered palette checks](artifacts/filtered-preview-checks.txt)
- [Automatic preview configurations](artifacts/automatic-preview-defaults-log.txt)
