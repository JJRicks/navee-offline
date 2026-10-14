# Unified ride analysis

This change covers update notes 9–12, 15–16 (Rides controls), 31–36 and 40. It combines phone and scooter readings in the same overview, puts the route first, groups key ride figures, and adds an append-only pencil rename action. App preferences now owns basemap/provider selection; USGS and basemap-on are the defaults. No account, server-side route processing, or map token is added.

## Data and display behavior

- Eight independently selectable overlays: power, wheel speed, GPS speed, voltage, battery %, relative elevation, GPS acceleration and instantaneous energy/distance. Power and wheel speed start selected; subsequent choices save immediately for other rides.
- Each selected series adds its own colored Y scale. On narrow screens the scale rail scrolls separately while the plot retains most of the width. Color and line pattern identify each series. Battery uses 0–100%; speed scale labels retain tenths.
- The expanded graph supports native two-finger time zoom, time pan, double-tap reset and accessible zoom/Fit buttons. Plot gaps follow journal segments and missing readings. Phone samples align to the scooter timeline using matching monotonic clocks, including delayed phone capture start.
- Instantaneous W / km/h yields Wh/km (converted to Wh/mi when appropriate). Speeds below 3 km/h and absent/nonfinite values produce gaps, not huge stationary ratios or synthetic zeros. Negative battery input remains negative.
- Blue-to-yellow magnitude/elevation ramps and blue/neutral/orange grade/acceleration ramps replace red/green route colors. Legends state units and scale; missing route values use dashed gray lines. Elevation is relative to the recording segment’s source baseline.
- Elevation filtering is derived analysis only. It checks GPS and barometer height changes against the last accepted reading and a rolling checkpoint, allowing 4 m noise and a 2.5 m/s vertical rate. A rejected excursion does not widen the re-entry tolerance; a prolonged loss reanchors after 20 seconds. Rejected heights break derived grade/ascent anchors. Raw journal/altitude exports remain available, and analysis reports the rejected-elevation count. This is a heuristic, not a calibrated terrain survey.
- New rides use the established positive-current-is-draw convention. The redundant direction control is removed; historical journals preserve their original interpretation.
- Rename appends a RENAME journal event and refreshes title/list/analysis caches without modifying measurement rows. Journal work runs off the UI thread.

## Validation

Pure suites: `bash tests/run.sh` passes 46,254 checks. New cases cover delayed phone alignment, unit conversion, low-speed/nonfinite energy ratios, no invented phone data, gap segmentation, meaningful battery bounds, rename/replay/cache behavior, GPS and smoothed-pressure elevation spikes, valid GPS retention, and raw altitude preservation. Existing sustained-hill/grade fixtures continue to pass.

`bash build.sh` and `bash android-tests/build.sh` pass with the retained signing identity. Build output contains existing Java 8/JDK native-access warnings, not new compiler errors.

`RidesAnalysisValidation` is admitted only on an empty Android emulator with Bluetooth off. It seeds synthetic local ride/phone journals; it never connects a scooter or invokes production recording. Passed 33 checks at each of 344×882 dp (dark), 725×604 dp (dark), and 360×800 dp with 140% system font (light). Checks include map/source preferences, phone-placement selector, default/multiple/all-eight graph selections, immediate persistence, actual two-pointer zoom, separate scale-rail scrolling, map elevation selection, fullscreen sizing, and pencil rename. Screenshots were inspected for compact/expanded layouts and large text.

The pre-existing UI organization fixture’s map segment now opts out of basemaps explicitly and tests provider changes/canceled tile callbacks without depending on the removed Rides provider controls.

Reproduce on a disposable emulator (the runner uninstalls its app packages):

```sh
python3 android-tests/run-rides.py --adb /path/to/adb --serial emulator-5590 \
  --app /path/to/preview.apk --runner /path/to/parked-validation.apk \
  --out /path/to/evidence
```

## Limits

No physical ride, phone sensor accuracy, network tile availability, or scooter command behavior is certified by these synthetic checks. The raw journals remain the authoritative export. Existing phone pressure/GPS grade and acceleration limitations still apply. There is no release or owner-phone installation in this PR.
