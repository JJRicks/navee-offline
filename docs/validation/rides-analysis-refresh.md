# Unified ride analysis

This change covers update notes 9–12, 15–16 (Rides controls), 31–36 and 40. It combines phone and scooter readings in the same overview, puts the route first, groups key ride figures, and adds an append-only pencil rename action. App preferences now owns basemap/provider selection; USGS and basemap-on are the defaults. No account, server-side route processing, or map token is added.

## Data and display behavior

- Eight independently selectable overlays: power, wheel speed, GPS speed, voltage, battery %, relative elevation, GPS acceleration and instantaneous energy/distance. Power and wheel speed start selected; subsequent choices save immediately for other rides.
- Each selected series adds its own colored Y scale. On narrow screens the scale rail scrolls separately while the plot retains most of the width. Color and line pattern identify each series. Battery uses 0–100%; speed scale labels retain tenths.
- Inline and expanded graphs support tap/horizontal slide inspection, native two-finger time zoom/pan, time-label dragging, double-tap reset and accessible zoom/Fit buttons. Vertical drags yield to the surrounding page. Expanded overlay choices scroll within a bounded region so the chart remains usable in short windows or with large text.
- The inspection rail shows each selected series' actual plotted sample, converted to the displayed unit, together with that sample's aligned elapsed timestamp. It never interpolates numeric values, carries values outside their recorded extent, or crosses missing/segmented/long-gap intervals. Independent phone and scooter sampling can yield different timestamps, which are shown separately. The inline preview stays capped at 600 scooter samples (and the existing bounded phone preview). Expanding immediately opens that preview, then a dedicated worker replays every committed scooter sample and every accepted phone fix from the original journals. The full graph and its inspection rail use that complete source; no fixed sample cap is applied to fullscreen. Existing speed formatting stays at tenths, and other inspected values keep their existing two decimal places. Raw journal export remains available.
- Instantaneous W / km/h yields Wh/km (converted to Wh/mi when appropriate). Speeds below 3 km/h and absent/nonfinite values produce gaps, not huge stationary ratios or synthetic zeros. Negative battery input remains negative.
- Blue-to-yellow magnitude/elevation ramps and blue/neutral/orange grade/acceleration ramps replace red/green route colors. Legends state units and scale; missing route values use dashed gray lines. Elevation is relative to the recording segment’s source baseline.
- Elevation filtering is derived analysis only. It checks GPS and barometer height changes against the last accepted reading and a rolling checkpoint, allowing 4 m noise and a 2.5 m/s vertical rate. A rejected excursion does not widen the re-entry tolerance; a prolonged loss reanchors after 20 seconds. Rejected heights break derived grade/ascent anchors. Raw journal/altitude exports remain available, and analysis reports the rejected-elevation count. This is a heuristic, not a calibrated terrain survey.
- New rides use the established positive-current-is-draw convention. The redundant direction control is removed; historical journals preserve their original interpretation.
- Rename appends a RENAME journal event and refreshes title/list/analysis caches without modifying measurement rows. Journal work runs off the UI thread.

## Validation

Pure suites: `bash tests/run.sh` passes 46,314 checks. New cases cover delayed phone alignment, unit conversion, low-speed/nonfinite energy ratios, no invented phone data, gap segmentation, meaningful battery bounds, rename/replay/cache behavior, GPS and smoothed-pressure elevation spikes, valid GPS retention, and raw altitude preservation. Inspection regressions verify identity with the original sample, precision before formatting, nearest-sample selection, no fabricated readings at missing/segment/long-gap spans, delayed phone timing, and safe nonmonotonic/empty timelines. Existing sustained-hill/grade fixtures continue to pass.

`bash build.sh` and `bash android-tests/build.sh` pass with the retained signing identity. Build output contains existing Java 8/JDK native-access warnings, not new compiler errors.

`RidesAnalysisValidation` is admitted only on an empty Android emulator with Bluetooth off. It seeds synthetic local ride/phone journals; it never connects a scooter or invokes production recording. Passed 84 checks at each of 344×882 dp (dark), 725×604 dp (dark), and 360×800 dp with 140% system font (light). Checks include map/source preferences, phone-placement selector, default/multiple/all-eight graph selections, immediate persistence, actual two-pointer zoom/pan, separate scale-rail scrolling, map elevation selection, fullscreen sizing, and pencil rename. The inline and expanded fixtures both tap and slide to verify per-series sample values/units/times, an unchanged viewport while scrubbing, vertical gesture handoff, and time-label panning; all expanded overlay controls remain reachable. Screenshots were inspected for compact/expanded layouts and large text. The review follow-up also covers route-casing contrast, overlay-label fit, zoom-button bounds, an already-open dialog transitioning from sensor saving to a loaded map, empty phone journals, and Unicode-safe input checks.

The pre-existing UI organization fixture’s map segment now opts out of basemaps explicitly and tests provider changes/canceled tile callbacks without depending on the removed Rides provider controls.

Synthetic ride seeding uses an isolated store and attaches it to the service only after the journal is stopped, preventing real-time service heartbeats from changing the fixture's artificial clock or introducing unrelated gaps.

Reproduce on a disposable emulator (the runner uninstalls its app packages):

```sh
python3 android-tests/run-rides.py --adb /path/to/adb --serial emulator-5590 \
  --app /path/to/preview.apk --runner /path/to/parked-validation.apk \
  --out /path/to/evidence
```

## Full-detail follow-up

- Fullscreen shows `Loading…`, then `All samples` after both journals and all chart series have been prepared off the UI thread. A separate reader avoids holding the live recording store monitor while replaying an older ride. File size/modification checks reject a journal changed during loading. Finishing phone saves leave the preview visible with Retry.
- Dismissing fullscreen, detaching its source ride, changing screens, or destroying the activity cancels pending work. Interrupted parsers close their readers, and generation/attachment checks reject late results. Retrying creates a fresh snapshot. The inline graph stays lightweight.
- Source-clock alignment indexes scooter timestamps once instead of scanning every scooter sample for each GPS fix. Boot/wall-clock matching, per-series elapsed timestamps, display units, missing values, and segment gaps retain their previous semantics.
- Drawing uses the visible time range and caches a pixel-column plan. Each continuous bucket retains its first, last, minimum, and maximum samples in source order, and every missing-value/segment boundary starts a separate path. Isolated valid samples render as points. This only reduces drawing work: inspection always searches the full source list, including brief spikes and zoomed-in intermediate samples.
- There is no silent fallback to a thinned “full” graph. The existing 128 MiB phone-journal parser guard is unchanged. Full loading also stops if remaining Java heap falls below the smaller of 32 MiB or one eighth of maximum heap. This is a practical headroom guard, not a guaranteed recording-duration limit; the footprint depends on the recorded fields, rate, and phone. A visible `Preview · Retry` state and explanatory message preserve the preview on resource/file errors, with raw Export still available.

New pure fixtures replay 4,096 scooter frames and 6,500 accepted GPS fixes, recover single-frame peaks skipped by previews, preserve endpoints/gaps/missing values and source timestamps, test cancellation/retry and the oversized-phone-file guard, and exercise extrema-preserving drawing plus full-source inspection over 100,000 samples. The native fixture passes 102 checks each on compact 344×882 dp and unfolded 725×604 dp (204 total), including a 4,096-frame/fix recording, tap/slide to its off-preview peak, a deterministically blocked loader with responsive UI, cancellation on fullscreen close/source detachment, explicit Preview/Retry during pending sensor saving, and changing to a different ride without stale results. Compact and unfolded screenshots were inspected. The existing 84-check large-font result above predates this follow-up; it is not counted as a new full-detail run. Cold emulator background Google-service pressure was separate from app heap admission and did not change the product guard.

### Short-window and loader recovery follow-up

Combined validation exposed a collapsed fullscreen plot at 725×604 dp with 140% text. The fullscreen-only gesture hint now shares the bounded, scrolling overlay controls. That area adapts to the actual measured height, reserving more room for the graph when inspection labels appear; all overlay choices and the hint remain reachable. Inline layout is unchanged. New assertions retain the original 160 dp usable-chart threshold after inspection, Preview/Retry, full-data loading, and full-source scrubbing rather than weakening the threshold.

Unexpected worker `RuntimeException` and `OutOfMemoryError` now recover to the same explicit Preview/Retry state; memory-error recovery is best effort, and other fatal `Error` types are not blanket-caught. The fixture induces an unexpected worker failure without exhausting emulator memory and verifies a subsequent normal retry. The existing file and heap guards are unchanged. Time-axis labels use tenths below one-second spacing, omit duplicates, and avoid measured text overlap at maximum zoom. Speed and inspection-value formatting stay unchanged.

Signed app/runner builds pass. The focused 725×604 dp / 140% text / light-theme native run passes 123 checks, including retained plot height, scrollable controls/help, full-source readout bounds, worker failure/retry, distinct subsecond ticks, and the previous full-detail lifecycle/inspection fixtures. Final screenshots were inspected. The earlier pure and default-font native results above remain applicable to unchanged core behavior; they were not rerun for this UI/recovery follow-up. This case can be reproduced with `--case 725x604:1.4:light` on the runner above.

## Independent review follow-up

- Routes use a dark casing over basemaps and light canvases, and a white casing on bare dark canvases. Pale ramp values stay visible; missing values retain their dashed treatment.
- Chart zoom controls use symmetric layout margins, and the shorter Accel/Elev. choices fit the enlarged-text compact route selector.
- A reserved map placeholder explains pending sensor saves/loading. Open compact details refresh when the save finishes; rides with no location or motion samples omit the empty phone block.
- Rename input and journal limits preserve complete UTF-16 surrogate pairs.
- Time zoom draws only the visible sample span plus its boundary neighbors. Availability and sample cadence are cached when a series is built; an unordered-time fallback preserves older data. Pure regressions cover bounds on 10,000 samples and complete emoji at the length limit.

## Limits

No physical ride, phone sensor accuracy, network tile availability, or scooter command behavior is certified by these synthetic checks. The raw journals remain the authoritative export. Existing phone pressure/GPS grade and acceleration limitations still apply. There is no release or owner-phone installation in this PR.
