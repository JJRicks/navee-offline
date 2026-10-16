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
`STOCK_REFERENCE`, and `PERSONAL`; the two targets can have different sources.

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

More → Developer options → Charge recordings lists saved charges. Both graph
modes retain all recorded samples and offer touch/drag inspection and fullscreen:

- Time remaining compares the stored prediction with elapsed time to the observed
  finish. An accurate line falls one minute per minute.
- Predicted finish compares the sample time plus its stored prediction with a
  constant observed-finish line. A stable prediction is horizontal.

The reference is the **observed arrival at displayed 100%**, not a claim that
balancing or all charging current has ended. It requires `COMPLETE`, a terminal
100% sample, and a valid preceding below-100% sample within 25 seconds without a
gap. A stopped/interrupted recording or a reconnect already at 100% has no finish
reference. A lower configured cutoff is never compared against a full-charge
finish. It remains visible as a separate Saved limit prediction view.

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
both graph modes, raw inspection, fullscreen, saved limits, legacy records, and
profile-switch dismissal. Run it with `android-tests/run-charge-capture.py --suite
estimates` on a disposable emulator. The default `capture` suite retains existing
screen-off/recovery/notification checks. Neither runner admits owner phones.

These tests validate the recording and comparison implementation with synthetic
samples. They do not establish physical charging accuracy.

Executed October 15, 2026:

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
