# Recorded charging predictions

Charge samples save the prediction made at capture time by the same service method
used by the dashboard. The foreground recording service evaluates it even when no
widget or Activity is visible. Opening saved history never re-runs the estimator.
This change does not alter the prediction algorithm or its training inputs.

## Stored data and compatibility

New journals use `charge-v2`; `charge-v1` journals remain readable and retain their
original measurements. Each v2 sample appends estimator version, remaining minutes
to full, remaining minutes to the configured limit, target percentage, full/limit
source and the number of learned sessions. Missing predictions have blank numeric
fields and still retain the estimator version. The sources are `CURRENT_RATE`,
`STOCK_REFERENCE`, `FAST_REFERENCE`, and `PERSONAL`; the two targets can have different sources.

The JSON export retains `navee-charge-recordings-v1` with additive fields:

- `samples[].estimate`: null for legacy samples, otherwise an object containing
  `estimator_version`, `remaining_minutes_to_full`, `remaining_minutes_to_limit`,
  `target_percent`, `full_source`, `limit_source`, and `learned_sessions`.
- `observed_complete_elapsed_ms`: the monotonic timestamp of a supported observed
  arrival at displayed 100%, or null. This uses the same time base as the existing
  `elapsed_ms` fields; it is not a duration since the session began.

The estimator version is independent of the APK version. Change
`ChargingEstimate.ESTIMATOR_VERSION` when the estimator/reference behavior changes.
Personal curves can change between recordings even when this identifier stays the
same; saved values, sources, and learned-session count preserve what was predicted.
No predictions feed back into learned charging curves: learning still uses measured
SOC, current, voltage and timing only.

Bounds remain 20 journals, 18 hours and 6,500 samples per journal. The byte cap is
3.5 MB per journal to accommodate the longest allowed prediction rows; typical
files are smaller. History parses closed journals on the analysis worker without
holding the capture lock during disk reads. Results are immutable and profile
scoped. App backups preserve the new files without a separate migration.

## Graph interpretation

More → Developer options → Charge recordings lists saved charges. Each recording
uses the same multi-series chart renderer as ride recordings: colored category
toggles, fullscreen, pinch zoom, timeline panning, Fit and touch/drag inspection.
Drawing uses pixel buckets that retain spikes; inspection keeps every raw sample.
Selections are remembered separately from ride-chart selections. Opening/closing
fullscreen preserves the chosen categories, zoom and inspected sample.

Power, battery percentage, estimated finish and estimated countdown start enabled.
When the finish was observed, its horizontal reference line also starts enabled.
Estimated and observed finish times share an axis range, as do estimated and actual
countdowns. Other quantities use independent colored scales, as in ride graphs.
The axis strip can be scrolled horizontally when the scales do not all fit.

Optional categories include voltage, signed current, temperature, actual countdown,
and saved charging-limit percentage and predictions when a lower limit was used.
Clock labels respect the app's time format, showing seconds for short ranges and
dates across midnight; temperature respects °C/°F. Short fullscreen windows
scroll vertically rather than flattening the plot. Accessible previous/next
actions step through real recorded samples. Raw sample
inspection shows recorded estimator version and source beside saved predictions.
An accurate countdown falls one minute per minute; a stable finish prediction is
horizontal and converges toward the observed finish.

The reference is the **observed arrival at displayed 100%**, not a claim that
balancing or all charging current has ended. It requires `COMPLETE`, a terminal
100% sample, and a valid preceding below-100% sample within 25 seconds without a
gap. A stopped/interrupted recording or a reconnect already at 100% has no finish
reference. A lower configured cutoff is never compared against a full-charge
finish. It remains available through separate saved-limit categories.

Elapsed time is monotonic. Dates and finish clocks use the session's starting
wall-clock anchor plus elapsed time, so later phone-clock corrections do not look
like estimator drift. Original wall timestamps are still exported unchanged.
Missing predictions and connection gaps break prediction lines; missing estimates
alone do not erase a valid observed reference. Historical predictions are never
invented for legacy recordings.

## Validation

Pure tests cover v1 compatibility, exact v2 persistence/export, unavailable
estimates, mixed sources, malformed optional metadata, clock jumps, target changes,
connection gaps, conservative completion, profile isolation, immutable results,
6,500-sample retention, and independence of learning from saved predictions.

The isolated native runner `ChargeEstimateValidation` exercises the actual service
snapshot method without an Activity-dependent calculation, offline list/detail,
category toggles, raw inspection, fullscreen zoom/state retention, saved limits, legacy records, and
profile-switch dismissal. Run it with `android-tests/run-charge-capture.py --suite
estimates` on a disposable emulator. The default `capture` suite retains existing
screen-off/recovery/notification checks. Neither runner admits owner phones.

These tests validate the recording and comparison implementation with synthetic
samples. They do not establish physical charging accuracy.

Original v1.2.0 validation, October 15, 2026:

- All 30 core suites passed, including 6,591 new prediction-history checks
  (70,671 total assertions/checks).
- Prediction UI: 85 assertions each at 344×882 dp dark/100% system font,
  725×604 dp light/140%, and 600×344 dp dark/140%. Includes finite chart geometry
  for both `Double.MIN_VALUE` and `Double.MAX_VALUE`, safe system-bar insets,
  and legacy missing-prediction labels distinct from connection gaps.
- Existing capture UI: 174 assertions each at 344×882 dp dark/100% and
  725×604 dp light/140%.
- Focused dashboard validation: both widths passed, including real tile actions
  against the built-in simulation for Off/On, pending and edit-mode guards, plus
  disconnected appearance. The runner reports zero real scooter mutations.
- App and native runner built and passed APK signature/alignment verification.
- Independent Astra Ultra and Sol Ultra reviews completed, with numeric-range
  follow-up fixes verified before handoff.

Unified-chart follow-up, October 15, 2026:

- All core suites pass: 77,300 checks, including 6,605 adapter checks for
  all 6,500 recorded samples, gap separation, observed comparisons and extreme
  finite bounds; backup preference type rejection is covered.
- Combined charge UI: 226 assertions each at 344×882 dp dark/100% system font,
  725×604 dp light/140%, and 600×344 dp dark/140%. Covers the actual plot area,
  category selection, fullscreen state retention, pinch zoom, raw inspection,
  accessible sample stepping, midnight labels, 12/24-hour clocks and Fahrenheit.
- Independent Astra Ultra and Sol Ultra reviews completed; verified feedback
  about accessibility, backup validation and clock labels was addressed.
- Existing ride analysis: 181 assertions each at 344×882 dp dark/100% and
  725×604 dp light/140%, including full-detail loading, raw inspection, axis
  scrolling and fullscreen interactions.
- Emulator fixtures are synthetic. No owner phone or scooter was involved.
