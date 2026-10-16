# Range learned from your own rides

**Scooter → Range estimate** has two choices: **Scooter's estimate** (the default, following drive mode) and **Volt's estimate (learned)**. Volt's forecast applies to Scooter overview, Your scooter and Battery overview. Each scooter has its own choice and learns only from that profile's completed recordings on this phone. No owner's learned-range coefficients or ride recordings ship with Volt.

Other numeric readings, gauges, charts, Live Stats and exported telemetry retain the scooter's original range. Choosing Volt's estimate changes only the local display. Explicitly selecting Scooter's estimate enables the existing drive-mode follower, whose firmware writes still require fresh, parked scooter state.

With insufficient usable history, Volt's option is greyed but remains tappable for a brief more-rides popup. The first usable selection shows a short introduction. Loading and storage failure have distinct messages, with an explicit retry after failure. The picker contains no detailed ride-count, distance, battery-point or temperature evidence block.

## Calculation

The estimate divides total recorded odometer distance by total battery percentage-point drop, then multiplies by the currently displayed battery level. It pools distances and drops instead of averaging individual ride ratios. A short efficient return trip therefore contributes alongside a harder uphill trip. It uses rides from the last 90 days and starts after at least three usable rides, 20 battery points, and 3 km. Until then—or if the current battery level is missing—the widget keeps the scooter’s estimate.

A stale battery reading produces a stale forecast. Simulation uses sample telemetry and never reads or trains on genuine history.

This is a preliminary forecast for similar riding, not a measurement of physical capacity or battery health. Integer battery/odometer rounding, hills, speed, payload, wind, cold, and unobserved battery levels can change actual range. There is no fitted correction for a route, mode, or temperature without enough independent evidence. It does not predict a guaranteed distance to shutdown.

## Evidence admission

The worker checks **every committed sample** in a single streaming pass per finished journal, retaining compact validation state instead of a full sample list. It does not judge quality from the 600-point chart preview. It rejects:

- Active or recovered recordings, gaps, clock changes, and odometer resets.
- Less than one minute recorded, 30 seconds moving, or 0.2 km traveled.
- Drops below two battery points, which are dominated by gauge rounding.
- Speed or battery-sample coverage below 95%.
- Missing battery/odometer endpoints within three seconds of the recording ends.
- Distance disagreement beyond the larger of 0.3 km and 25% (allowing coarse odometer rounding).
- A rise greater than one point above the lowest observed battery level, or any fresh nonzero charging-state sample.

Duplicate ride IDs contribute once. Missing intervals and stationary recordings never become traveled distance. The initial evidence threshold is an admission rule, not a statistical confidence guarantee.

Analysis distinguishes ready, insufficient and unavailable results. Unreadable or unfinished journals cannot silently masquerade as an empty ride history: if the remaining valid rides are insufficient, analysis reports unavailable. Independently checked rides may still form an estimate when they meet the threshold. A journal that changes during replay triggers one coherent retry. Analysis never repairs or rewrites a journal.

## Lifecycle and tests

Analysis runs off the UI thread with per-profile caching. Completing/deleting a ride and the existing recording-data invalidation hook clear both capacity and range estimates. Full app restore and [ride-only restore](../ride-backups.md) pause the app for data maintenance and require it to close and reopen. Both recreate the service and its models, so restored rides are analyzed with fresh caches; no explicit post-import invalidation call is needed. Ride-only restore preserves known profile associations and requires an explicit destination for unknown profiles. Identical recordings with the same ID are skipped and local conflicts are retained, avoiding duplicate learning contributions. Cached evidence also ages out daily. Selecting Volt changes only the display; explicitly selecting Scooter enables the guarded drive-mode follower. No learned range is written into raw telemetry, ride journals or the history database.

`LearnedRangeEstimateTest` uses synthetic committed journals to check pooling, profile/date isolation, duplicates, short efficient legs, evidence thresholds, missing data, clock/reset/gap rejection, charging hidden in long recordings, streaming admission, and unavailable/zero battery behavior. Its large-journal case exercises streaming under memory pressure without relying on the full-detail chart loader.

`LearnedRangeValidation` is guarded for an empty disposable emulator. It checks the asynchronous worker, opt-in persistence, raw-value preservation, stale presentation, switching scooters, simulation, delete/relearn invalidation, the settings dialog, and absence of Bluetooth work. `android-tests/run-learned-range.py` drives the same folded/unfolded size and text-scale cases as other focused UI checks.

Historical 1.3.0 verification on October 16, 2026: the full pure-Java suite passed (32 learned-range assertions; 103 encrypted-backup checks including the new preference type), and the app/validation APKs built and verified. The disposable emulator passed 40 integration assertions at both 344×882 dark/1.0 text and 725×604 light/1.4 text. Those results predate the Scooter picker and streaming-analysis follow-up. Real scooter range accuracy is not established by UI or synthetic tests.

Follow-up 1.3.1 verification on October 16, 2026: all 34 core suites pass (80,719 checks, including 57 learned-range assertions and the constrained-memory subprocess). Native picker/service checks pass 156 assertions at each of the same two sizes, including completion of a third ride through the service while the picker remains open, immediate taps after invalidation, and simulation messaging. Independent Astra Ultra and Sol Ultra reviews found no remaining actionable issues after the lifecycle fixes.
