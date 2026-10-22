# Range learned from your own rides

**Scooter → Range estimate** has two choices: **Scooter's estimate** (the default, following drive mode) and **Volt's estimate (learned)**. Volt's forecast applies to all current-range dashboard widgets and the Live Stats overview range headline. Each scooter has its own choice and learns only from that profile's completed recordings on this phone. No owner's learned-range coefficients or ride recordings ship with Volt.

Explicit **Scooter range** readings/favourites, recorded charts and exported telemetry retain the scooter's original range. Dashboard trend captions and Stats chart titles identify that source; the learned estimate is never spliced into a recorded scooter series. Choosing Volt's estimate changes only the local display. Explicitly selecting Scooter's estimate enables the existing drive-mode follower, whose firmware writes still require fresh, parked scooter state.

With insufficient usable history, Volt's option is greyed but remains tappable for a brief more-rides popup. The first usable selection shows a short introduction. Loading and storage failure have distinct messages, with an explicit retry after failure. The picker contains no detailed ride-count, distance, battery-point or temperature evidence block.

## Calculation

**With recorded power** (the scooter reports voltage and current, and the ride's current direction is confirmed and unchanged), the forecast has two independent parts:

1. **Battery energy left.** Each fully counted gauge point (entered by a one-point drop and left by the next, with no gap, charging, rise or skipped point in between, and power readings for at least 95% of it) contributes its net energy to one of ten 10-point bands. A band with at least three points uses its mean, pulled toward the pooled mean by eight pseudo-points so a thin band can't swing the forecast. Bands below the lowest one seen assume **10% less energy per point than the smallest band seen**. Voltage keeps falling near empty, and no recording shows that region. Other unseen bands use the nearer seen band. A whole reading is taken as the middle of its gauge point, so 100% holds 99.5 points.
2. **Energy per km.** Total net energy divided by total odometer distance, over rides with confirmed current direction and power readings for at least 95% of the ride. Missing power samples are scaled in. This doesn't use the gauge, so a ride with a short recording gap still counts here even though it can't count gauge points across the gap.

Remaining range is battery energy left at the displayed level ÷ energy per km. It needs 20 counted gauge points, three energy rides and 3 km in the last 90 days.

**Likely range.** Consecutive energy rides, oldest first, are grouped into stretches of at least 6 km. For each stretch, actual km is divided by the km the forecast gives for the same gauge readings. The 5th and 95th percentiles of that ratio scale the forecast, and the forecast always stays inside. At least eight stretches are needed; with fewer the picker shows the forecast alone. The picker shows it as **About 10 km at 50% · likely 8–12 km**. Home widgets keep the single "about" value.

**Without power readings** (or with an unconfirmed current direction), the earlier model is unchanged: total recorded odometer distance ÷ total battery-percentage drop, multiplied by the displayed battery level. It pools distances and drops instead of averaging individual ride ratios, uses rides from the last 90 days, and starts after at least three usable rides, 20 battery points and 3 km.

Until either model has enough evidence, or if the current battery level is missing, the widget keeps the scooter's estimate. A stale battery reading produces a stale forecast. Simulation uses sample telemetry and never reads or trains on genuine history.

This is a preliminary forecast for similar riding, not a measurement of physical capacity or battery health. Hills, speed, payload, wind, cold and unobserved battery levels can change actual range. It does not predict a guaranteed distance to shutdown.

**Not done, deliberately:**
- **Blending in the current ride's own rate.** On the recorded rides, energy per km follows the route far more than the rider. Rides that start at 100% (just after charging, apparently downhill) used 14–19 Wh/km; slow, climbing rides used 40–46. Extrapolating a downhill ride would overstate range.
- **Weighting recent rides or temperature.** The recordings span ten days at 12–31 °C, so neither can be validated yet.

## Evidence admission

The worker checks **every committed sample** in a single streaming pass per finished journal, retaining compact validation state instead of a full sample list. It does not judge quality from the 600-point chart preview.

**Distance model.** It rejects:

- Active or recovered recordings, gaps, clock changes, and odometer resets. One exception: a ride Volt [ended automatically](ride-auto-stop.md) is judged at its last complete reading when its only gap is the final disconnection and that tail is at most 60 seconds. The journal keeps the real gap and stop time.
- Less than one minute recorded, 30 seconds moving, or 0.2 km traveled.
- Drops below two battery points, which are dominated by gauge rounding.
- Speed or battery-sample coverage below 95%.
- Missing battery/odometer endpoints within three seconds of the recording ends.
- Distance disagreement beyond the larger of 0.3 km and 25% (allowing coarse odometer rounding).
- A rise greater than one point above the lowest observed battery level, or any fresh nonzero charging-state sample.

**Energy model.**
- **Energy per km** applies the same movement, distance-agreement, reset, recovery and clock rules. It allows short gaps while power and speed cover at least 95% of the ride, judged at the last complete reading after an automatic parked stop. It rejects any charging, any unconfirmed-direction interval and a direction change.
- **Gauge points** stop at every break and are counted only between two exact one-point drops. A gauge that jumps several points at once contributes nothing for those points.

Duplicate ride IDs contribute once. Missing intervals and stationary recordings never become traveled distance. The initial evidence threshold is an admission rule, not a statistical confidence guarantee.

Analysis distinguishes ready, insufficient and unavailable results. Unreadable or unfinished journals cannot silently masquerade as an empty ride history: if the remaining valid rides are insufficient, analysis reports unavailable. Independently checked rides may still form an estimate when they meet the threshold. A journal that changes during replay triggers one coherent retry. Analysis never repairs or rewrites a journal.

## Replay on recorded rides

The owner's backup (34 rides on one UT5 MAX, one ten-day period) was decrypted and replayed locally through `RideStore` and `LearnedRangeEstimate`. Only the aggregates below are published.

**Evidence.**
- 24 rides pass the distance model.
- 25 rides (52.9 km) give energy per km. They include the 57%→22% and 100%→90% rides, which the distance model rejects for a missing endpoint.
- 274 gauge points are fully counted, from 100% down to 17%. Eight rejected rides contribute points from their gap-free stretches, among them 21 points from a 40%→18% ride with recording gaps.

**Energy per gauge point by band.** These are the model's values: the band mean pulled toward the pooled mean.

| Band | 91–100 | 81–90 | 71–80 | 61–70 | 51–60 | 41–50 | 31–40 | 21–30 | 11–20 | 1–10 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| Wh per point | 6.61 | 6.43 | 6.25 | 6.13 | 6.11 | 5.55 | 4.88 | 6.01 | 6.06 (5 pts) | 4.39 (none seen) |

- Charge per point is steady at about 0.134 Ah. The fall in energy per point from the top is the pack voltage, about 51.7 V under load near full against 45.2 V below 30%.
- The dip at 31–50% is the gauge catching up. Two of the four rides through that region dropped points at 0.07–0.10 Ah each; the longest low-battery ride counted normally.

**Forecast, km.**

| Battery | 100% | 80% | 50% | 30% | 20% | 10% |
|---|---:|---:|---:|---:|---:|---:|
| Energy model | 20.7 (likely 17.4–25.0) | 16.0 | 9.5 | 5.8 | 3.6 | 1.5 |
| Earlier distance model | 21.4 | 17.1 | 10.7 | 6.4 | 4.3 | 2.1 |

The earlier model assumes every point is worth the same distance, so it reads progressively higher toward empty.

**Held-out accuracy.** Each run of consecutive rides is predicted from the other rides, or only from earlier rides. + means the forecast was longer than the ride.

| Stretch | Energy model, 10th / median / 90th % | Earlier model | Likely range contained the actual km |
|---|---|---|---|
| ≥ 6 km, others | −9 / +1 / +17% | −11 / 0 / +28% | 17 of 23 |
| ≥ 6 km, earlier only | −24 / 0 / +18% | −31 / −3 / +21% | 9 of 14 |
| ≥ 10 km, others | −6 / +2 / +10% | −10 / +1 / +8% | 19 of 22 |
| ≥ 10 km, earlier only | −16 / −3 / +15% | −16 / −2 / +14% | 10 of 13 |

Over one ride, error is dominated by the route: the median is about 30% for either model. Treat the likely range as a typical spread, not a guarantee; the held-out runs fall outside it about a quarter of the time.

**Stability.** With only earlier rides, the energy model was ready before the eighth ride, at 23.2 km for a full charge. From the ninth ride on, it stayed between 18.5 and 20.9 km.

## Lifecycle and tests

Analysis runs off the UI thread with per-profile caching. Completing/deleting a ride and the existing recording-data invalidation hook clear both capacity and range estimates. Full app restore and [ride-only restore](../ride-backups.md) pause the app for data maintenance and require it to close and reopen. Both recreate the service and its models, so restored rides are analyzed with fresh caches; no explicit post-import invalidation call is needed. Ride-only restore preserves known profile associations and requires an explicit destination for unknown profiles. Identical recordings with the same ID are skipped and local conflicts are retained, avoiding duplicate learning contributions. Cached evidence also ages out daily. Selecting Volt changes only the display; explicitly selecting Scooter enables the guarded drive-mode follower. No learned range is written into raw telemetry, ride journals or the history database.

`LearnedRangeEstimateTest` uses synthetic committed journals to check pooling, profile/date isolation, duplicates, short efficient legs, evidence thresholds, missing data, clock/reset/gap rejection, charging hidden in long recordings, streaming admission, and unavailable/zero battery behavior. Its energy cases check:

- the band, floor and midpoint arithmetic, plus monotonicity;
- both current conventions, regeneration and unconfirmed direction;
- gaps, charging, gauge jumps and sparse power;
- streaming parity;
- the likely-range percentiles and the eight-stretch minimum. Its large-journal case exercises streaming under memory pressure without relying on the full-detail chart loader.

`LearnedRangeValidation` is guarded for an empty disposable emulator. It checks the asynchronous worker, opt-in persistence, raw-value preservation, stale presentation, switching scooters, simulation, delete/relearn invalidation, the settings dialog, and absence of Bluetooth work. Current-display checks contrast a 30 km scooter report with an 11 km learned estimate across every range-capable dashboard widget and the Stats headline in km, mi and dual-unit modes, while checking that labelled raw readings and charts retain their source. `android-tests/run-learned-range.py` drives the same folded/unfolded size and text-scale cases as other focused UI checks.

Historical 1.3.0 verification on October 16, 2026: the full pure-Java suite passed (32 learned-range assertions; 103 encrypted-backup checks including the new preference type), and the app/validation APKs built and verified. The disposable emulator passed 40 integration assertions at both 344×882 dark/1.0 text and 725×604 light/1.4 text. Those results predate the Scooter picker and streaming-analysis follow-up. Real scooter range accuracy is not established by UI or synthetic tests.

Follow-up 1.3.1 verification on October 16, 2026: all 34 core suites pass (80,719 checks, including 57 learned-range assertions and the constrained-memory subprocess). Native picker/service checks pass 156 assertions at each of the same two sizes, including completion of a third ride through the service while the picker remains open, immediate taps after invalidation, and simulation messaging. Independent Astra Ultra and Sol Ultra reviews found no remaining actionable issues after the lifecycle fixes.
