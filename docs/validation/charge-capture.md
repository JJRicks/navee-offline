# Charger-specific recording and estimates

Validated October 14, 2026. This change is stacked on
`codex/preferences-and-charge-labels` (`abc0dba4d8bc77c26a67ea5e146c8f6be7f9fa78`).

## User-visible behavior

- Developer options has an explicit **Record this charge** action and a Stock /
  High-speed charger selector. The selection describes the charger the user
  attached; no hardware identification is inferred. It is saved per scooter.
- Starting requires a genuine authenticated connection to the selected scooter,
  fresh charging/SOC/paired electrical data, developer options, and enabled
  notifications. Simulation and concurrent ride capture are refused. The start
  confirmation describes the data, background operation, limits and local storage.
- An explicit capture keeps Bluetooth polling and a foreground Stop notification
  alive when the app is hidden. Normal background-connection preferences still
  apply after capture stops. Charge capture alone never reconnects after a loss.
- Capture stops when the user stops it, charging ends, the scooter disconnects,
  the profile changes, readings stop for 30 seconds, the service ends, or the
  storage/time cap is reached. It never silently resumes after a process restart.
- At 100%, the final fresh paired sample is saved before completion. Charging may
  already have changed to off and current may have fallen to zero. At 99%, a
  charging-off packet gets one bounded five-second poll window for the final SOC
  packet; missing data remains missing. Completion means the reported SOC reached
  100%, not that balancing or the charger's own final stage was measured.
- Dashboard charging estimates consult the service's selected scooter/charger
  curve. The existing fresh paired-electrical and rated-capacity gates remain in
  force. Learned estimates also require compatible live power at the current SOC.
  A different, absent or inconsistent power reference preserves the current-rate
  estimate, with an explanation. This checks plausibility; it does not identify
  which charger is connected or change the manual selection.

## Data and qualification

The app-private `files/charge-recordings` directory contains UUID-named journals.
Each starts with `charge-v1,profile-id,charger,start-wall-ms,start-monotonic-ms`,
then rows of `s,wall-ms,monotonic-ms,SOC,volts,signed-amps,signed-watts,temp-C,gap`,
and ends with a stop reason. Missing temperature is blank. No owner token, MAC,
location, or account data is copied into these files. Nothing is uploaded.

Samples are normally at most once every ten seconds. The final 100% sample may
be earlier. State, SOC, voltage and current must be finite and at most five
seconds old. Voltage/current must come from the same positive timestamp and
source opcode. Out-of-range values, electrical spikes and invalid samples are
rejected. Temperature is optional. SOC rollbacks, skipped percentages, gaps over
25 seconds and wall-clock discontinuities break the affected curve interval.

Retention is at most 20 journals, approximately 20 MB in total, with an 18-hour,
6,500-sample and approximately 1 MB cap per journal. Non-qualifying captures are
removed first. If all remaining files qualify, pruning removes the oldest file
from the largest profile/charger bucket, balancing coverage instead of allowing
frequent stock captures to evict a minority high-speed curve. Two sessions per
bucket are protected while the global cap can accommodate them; an active capture
reserves one of the 20 slots. The cap still takes precedence when too many buckets
compete. The analysis worker caches closed-file qualification for subsequent
pruning; changed files invalidate that cache. Abruptly interrupted journals
are finalized as interrupted on next launch and excluded from learning. A
per-scooter clear action removes that scooter's closed journals and derived curves.
Curve parsing runs on a dedicated worker; cached estimates are read on the UI
thread without disk parsing.

A qualifying session needs at least 61 samples, ten minutes, ten complete
one-percentage SOC intervals, and 90% contiguous time coverage. The initial
partial interval is excluded. Each estimated remaining percentage interval needs
at least two independently recorded qualifying sessions for this exact profile
and selected charger. Durations are averaged after rejecting strong outliers;
two strongly disagreeing sessions cannot establish a curve. Repeated intervals
within one recording do not count as independent evidence. A missing 99–100%
interval prevents a learned full-charge ETA; a covered lower cutoff may still
use its learned estimate. The remaining full-charge time then retains its stated
current-rate fallback, without inventing taper coverage.

Each completed one-percent interval also retains the median battery-side power
from at least three paired samples inside that interval. The next interval's
first sample, including a zero-current final cutoff packet, does not contaminate
the previous interval's power. At the current SOC, at least two duration-qualified
sessions must also provide consistent power (within 25% of their median). Live
power must then be within 35% of that reference. Taper is compared with recorded
power at the same SOC, rather than the charger's bulk power. Missing or conflicting
power fails closed to the current-rate estimate. These are conservative heuristic
tolerances pending hardware validation, not charger identification or an accuracy
guarantee; temperature and other changes can also cause a mismatch.

## Validation

- `bash tests/run.sh`: all 16 suites passed, 46,296 assertions/checks including 77
  charge-journal/curve checks. Existing charging estimates retain their 135 checks.
- The new checks cover freshness/source pairing, invalid values, cadence,
  retention, process interruption, profile/charger separation, gaps, clock jumps,
  rollback, outlier curves, insufficient data and missing taper. Two full
  synthetic 40–100% sessions with different durations yield an averaged 75-minute
  50–100% estimate. Two completed sessions with charging-off/zero-current final
  packets retain a learned one-minute 99–100% interval.
- Review regression: two 52 V / 2.75 A, three-minute-per-percent stock sessions
  cannot override the approximately 63-minute current-rate estimate at 60% with
  twice the live current. Matching stock power retains its learned 120-minute
  estimate. Recorded taper uses the same-SOC reference successfully; missing,
  zero, conflicting and out-of-tolerance power preserve the fallback. Tests also
  cover zero-current completion and the inclusive tolerance boundaries.
- Retention regressions preserve minority scooter/charger curves through 25
  qualifying stock captures and 30 short/failed captures while enforcing the
  same global file/byte bounds.
- App and separate instrumentation APK built and signature-verified.
- `ChargeCaptureValidation` passed 49 assertions per case on disposable emulator
  5588 initially and 5586 after the review fixes: 344×882 dp dark/default font and
  725×604 dp light/140% font. Screenshots
  were inspected for readable controls and wrapping.
- Native checks cover disconnected and simulation start refusal without creating
  files; per-profile charger selection; background-work admission only for active
  explicit capture; ride/simulation/charger-change exclusions; the notification's
  real Stop `PendingIntent`; disconnect/profile/service-end cleanup; no restart
  resumption; no GATT/scanner/phone-sensor use; and per-profile clearing.

The native lifecycle test intentionally seeds a low-level synthetic journal below
the authenticated-start guard. For the Stop notification assertion it temporarily
postpones the unrelated disconnected-frame handler, so only the notification's
manual-stop path can satisfy the assertion. It does not pretend a Bluetooth
connection or qualify synthetic data as real capture. The runner refuses physical
devices and pre-existing user data; Bluetooth stays disabled on the emulator.

Reproduction:

```sh
bash tests/run.sh
bash build.sh
bash android-tests/build.sh
python3 android-tests/run-charge-capture.py \
  --adb "$ANDROID_HOME/platform-tools/adb" --serial emulator-5588 \
  --app /path/to/test-app.apk --runner /path/to/parked-validation.apk \
  --out /path/to/disposable-evidence
```

Local evidence is under `work/charge-capture-validation/final-native` and
`work/charge-capture-validation/review-native`, with build and core logs alongside.
APKs/screenshots are not committed.

## Integration and remaining hardware validation

`ScooterService.chargingEstimate(Map<String, TelemetryHistory.Reading>)` returns
`ChargingEstimate.Result`. The result adds `learnedToLimit`, `learnedToFull`, and
`learnedSessions`; existing fields remain. The per-profile selection key is
`charge_charger_<profile-id>`, containing `STOCK` or `HIGH_SPEED`; missing means
unknown to the estimator. `chargeCharger()` / `setChargeCharger(...)` expose it.

No owner's phone or scooter was used. Successful authenticated capture, actual
stock/high-speed curves, radio behavior during a full screen-off charge, and
real-world ETA accuracy still require hardware testing. The conservative fallback
remains until the required data exists. This is an experimental observed-SOC
estimate, not a battery-health or charger-identification measurement.

The optional reconnect grace from review is deliberately deferred: disconnect
still ends an explicit capture immediately, and charge capture does not enable
automatic reconnection. This preserves the bounded, already-tested lifecycle
until full screen-off hardware captures can establish appropriate reconnect
behavior. Existing gap handling remains for data interruptions within a connection.
