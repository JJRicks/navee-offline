# Charge recovery and connection diagnostics

Volt 1.0.1, October 15, 2026.

An explicitly started charge capture can survive a temporary Bluetooth loss. Its
foreground notification and recording wake lock remain while the existing BLE
retry loop reconnects. Each interruption has a five-minute deadline, including
authentication and waiting for fresh readings. Partial reconnections cannot
extend that deadline. The original 18-hour capture limit still applies offline.

Recovery pins the selected profile, verified scooter serial and explicit
Disconnect generation from the start of capture. Resume requires authentication
to that same scooter and fresh charging state, SOC and paired voltage/current
from after the interruption. A gap precedes the resumed sample, even if the loss
was shorter than the normal sample interval. No missing samples are fabricated;
charging-curve intervals cannot bridge the gap. A fresh terminal sample can
record 100% without claiming the unobserved time was measured.

Stop and explicit Disconnect cancel recovery. Profile/identity changes, missing
permissions, disabled Bluetooth, fatal connection errors and the deadline stop
capture with an explicit reason. Process death does not silently restart capture;
the existing interrupted-journal behavior is retained. Recovery never changes
scooter settings or sends lock commands.

## Diagnostic retention

A device-local journal keeps at most 200 coarse events (under a 128 KiB file
limit). It records wall/monotonic time, the event reason, native status when
available, connection phase, UI/recording state and reading ages. It cannot accept
arbitrary message strings or packet payloads, and contains no owner identity,
PIN, scooter name, serial or Bluetooth address. The log is separate from telemetry
and portable app backups. Writes and startup loading run off the UI thread, with
one process-local writer per path and atomic replacement. A corrupt or unavailable
journal must not prevent connection or recording.

Stats → Diagnostics → Export includes the retained events, plus flags indicating
whether prior events finished loading and whether the most recent storage write
succeeded. A sudden process kill may lose events not yet written. This bounded
log is troubleshooting evidence, not a guaranteed crash recorder.

## Validation scope

Pure Java policy and journal tests cover recovery deadlines, same-scooter checks,
freshness, cancellation, marked gaps, storage bounds, restart loading and corrupt
files. Native checks exercise service/notification/wake-lock integration on a
disposable emulator with real Bluetooth disabled. Synthetic inputs are confined
to the test runner and never installed on the owner's phone.

The final candidate passed all 25 core suites (54,258 checks), 11 owner-capture
cleanup tests, and 118 native assertions each at 344×882dp dark/default font and
725×604dp light/140% font. Both APK builds and signature/alignment checks passed.

An earlier real overnight recording established two controller link-supervision
timeouts; it did not validate the new recovery behavior. Successful physical
reconnection and an uninterrupted overnight charge remain hardware follow-up.
