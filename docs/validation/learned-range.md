# Range learned from your own rides

In **More → App preferences → Range estimate**, choose **Learned** to use a local forecast in Scooter overview, My Scooter, and battery widgets. The default remains **Scooter**. Each scooter has its own choice and learns only from that profile’s completed recordings on this phone. No owner’s coefficients or recordings ship with Volt.

Other numeric readings, gauges, charts, Live Stats, and exported telemetry retain the scooter’s original range. This choice does not send a command or change the firmware’s separate range-mode setting.

## Calculation

The estimate divides total recorded odometer distance by total battery percentage-point drop, then multiplies by the currently displayed battery level. It pools distances and drops instead of averaging individual ride ratios. A short efficient return trip therefore contributes alongside a harder uphill trip. It uses rides from the last 90 days and starts after at least three usable rides, 20 battery points, and 3 km. Until then—or if the current battery level is missing—the widget keeps the scooter’s estimate.

The preference sheet reports usable rides, recorded distance and battery points, plus the battery levels and temperatures actually represented. A stale battery reading produces a stale forecast. Simulation uses sample telemetry and never reads or trains on genuine history.

This is a forecast for similar riding, not a measurement of physical capacity or battery health. Integer battery/odometer rounding, hills, speed, payload, wind, cold, and unobserved battery levels can change actual range. There is no fitted correction for a route, mode, or temperature without enough independent evidence. It does not predict a guaranteed distance to shutdown.

## Evidence admission

The worker loads **all committed samples** from each finished journal, one ride at a time; it does not judge quality from the 600-point chart preview. It rejects:

- Active or recovered recordings, gaps, clock changes, and odometer resets.
- Less than one minute recorded, 30 seconds moving, or 0.2 km traveled.
- Drops below two battery points, which are dominated by gauge rounding.
- Speed or battery-sample coverage below 95%.
- Missing battery/odometer endpoints within three seconds of the recording ends.
- Distance disagreement beyond the larger of 0.3 km and 25% (allowing coarse odometer rounding).
- A rise greater than one point above the lowest observed battery level, or any fresh nonzero charging-state sample.

Duplicate ride IDs contribute once. Missing intervals and stationary recordings never become traveled distance. The initial evidence threshold is an admission rule, not a statistical confidence guarantee.

## Lifecycle and tests

Analysis runs off the UI thread with per-profile caching. Completing/deleting a ride and the existing recording-data invalidation hook clear both capacity and range estimates. Full app restore recreates the service; ride-only restore must call `invalidateCapacityEstimate()` after import. Cached evidence also ages out daily. Preference changes affect display only. No learned range is written into raw telemetry, ride journals or the history database.

`LearnedRangeEstimateTest` uses synthetic committed journals to check pooling, profile/date isolation, duplicates, short efficient legs, evidence thresholds, missing data, clock/reset/gap rejection, charging hidden in long recordings, full-detail admission, and unavailable/zero battery behavior.

`LearnedRangeValidation` is guarded for an empty disposable emulator. It checks the asynchronous worker, opt-in persistence, raw-value preservation, stale presentation, switching scooters, simulation, delete/relearn invalidation, the settings dialog, and absence of Bluetooth work. `android-tests/run-learned-range.py` drives the same folded/unfolded size and text-scale cases as other focused UI checks.

Local verification on October 16, 2026: the full pure-Java suite passed (32 learned-range assertions; 103 encrypted-backup checks including the new preference type), and the app/validation APKs built and verified. The disposable emulator passed 40 integration assertions at both 344×882 dark/1.0 text and 725×604 light/1.4 text. The final help copy was shortened afterward without changing behavior. Real scooter range accuracy is not established by UI or synthetic tests.
