# Charge deadlines, route scrubbing and capacity sizing

## Behavior and evidence

Charge done by is a phone-side calculation which programs the scooter's existing one-time charge-start timer. It uses the next occurrence of the chosen local time, the current estimator result **to the configured charge limit**, and ten minutes of buffer. A due/past calculated start requests schedule-off (charge now); it never silently moves the start to tomorrow. UT5 MAX accepts minute precision. Other mapped models retain their whole-hour domain, rounded earlier using the scooter clock's captured offset.

The protocol has charging readback: status `0x70`, bytes 27–30 for the encoded start and byte 33 for the switch. Writes use `0x6f` selectors 6 (clock), 3 (start), and 1 (switch). Applying a plan serializes clock acknowledgement, exact timestamp acknowledgement/readback, then switch acknowledgement/readback. The clock itself has no independent readback. Confirmation remains protocol evidence, not proof that physical charging later starts.

No separate plugged-but-paused charger indicator has been established. Initial activation needs fresh active-charging telemetry and an available estimate. Once armed, the expected pause does not erase the intent; the owner can disable it while connected. No new wake lock, connection hold, alarm, background-policy exception or automatic ETA rescheduling is added. Reconnection never writes or rearms.

Confirmed intentions are scoped to a scooter, persisted only outside simulation, and included with scooter preferences in backups. Restoring them has no radio side effect. Zero or absent reboot readback does not cancel owned intent by itself. Positive changed time, target completion, sustained early charging after settling, elapsed deadline, and noncharging after the start reconcile local display state. A changed target preserves the actual start as manual instead of promising an obsolete finish. Partial writes and transport/profile/timezone changes are surfaced without claiming success.

## Automated checks

All cases use synthetic data, never owner recordings or a physical radio.

| Check | Result |
| --- | --- |
| Full pure-Java suite | Pass, including backup and shortcut regression tests |
| ChargeScheduleControllerTest | 216 assertions: 6:20 AM example, lower charge limits, early charge-now, whole-hour domains, DST gaps/overlaps, persistence, reconnect reconciliation, partial failure, expiry, stale/profile/session changes, and cancellation |
| Independent DST reproduction | Previously one hour wrong; now zero error |
| Android app + instrumentation build | Pass |
| ChargeScheduleWireValidation | 63 checks against real command queue/gates with an in-process GATT stand-in: ACK/readback order, forced-off, rejection, timeout, cancellation, exactly-once callbacks, queued safety refusal |
| ChargeScheduleValidation | 116 assertions each at 344×882 dp dark/default text and 725×604 dp light/140% text: rows, real disabled-thumb tap forwarding, time pickers, profile guards, simulated schedule/pause/cancel, manual flow, tiles, and isolation |
| RouteGestureValidation | 117 checks across compact/expanded sizes: on-route capture, off-line scrubbing, off-route pan/clear, cancellation, pinch handoff, gaps, single-point routes, crossing wobble and deliberate departure |
| RidesAnalysisValidation | 466 checks including inline/fullscreen map selection, pan, pinch and chart regressions |
| DashboardSlotsValidation | Pass: four-row defaults with saved/custom/historical geometry preserved |
| ResponsiveDashboardValidation `capacity` | Pass at 344×882 and 725×604 dp, plus 140% text at 344 dp. Sample, estimate, proxy, missing reference, processing and empty states fit without internal scrolling; Details retains a 48 dp target |

Independent reviewers checked each other's controller/service, UI, route and capacity changes. Fixed findings include DST decoding, a start becoming overdue while commands are confirmed, a changed charge target during the transaction, duplicate switch haptics, disabled child touch handling, and paused-plan cancellation availability. Native harness retries corrected two premature synchronous assertions; production callback timing was retained.

Screenshots were inspected for compact/unfolded charging controls and the compact capacity presentation. The physical phone, its recordings and its custom layouts were not touched.

## Remaining hardware validation

The scooter must still be tested with an actual charger: set a short future schedule, confirm charging pauses, disconnect the phone, and observe the scooter begin charging independently. Power-cycle persistence, a real overnight deadline, other models and unplugging during a scheduled pause are not established by emulator tests. Estimated completion is approximate and may change with the charger, temperature and battery behavior; a one-time plan intentionally does not adapt after disconnecting.
