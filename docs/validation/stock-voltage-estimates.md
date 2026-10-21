# Standard-charger voltage progress

Volt 1.4.8, estimator `ut5-stock-voltage-v4`.

## Change and scope

A UT5 MAX charge after a ride showed the percentage gauge catching up earlier:
roughly 82% at the same voltage where an earlier charge showed 89%. Similar
current and voltage gave similar actual time remaining. A percentage-only model
therefore overestimated bulk time by around 35 minutes. This supports voltage
and current matching, not a temperature multiplier or a battery-capacity change.

For UT5 MAX with the standard charger selected or confidently detected, fresh
paired electrical readings can refine the time to displayed 100% below 89%.
Two older completed standard-charger recordings supply 182 measured knots
(voltage, current and remaining time). The new warm charge does not enter the
reference. Inputs outside the observed voltage/current range retain the previous
method; a different reported capacity also retains it. Voltage entry/exit and
current compatibility blend toward the actual selected fallback, including personal
history when available. Overlapping reference
curves are weighted by current similarity, avoiding hard switches between them.
The two tiny marked recording breaks use their nearest measured endpoint, never
interpolated samples; larger marked holes are unsupported.

A matched voltage estimate takes priority over percentage-only personal full-time
estimates in this earlier stage. At 89% and above the entire existing finishing
and 99% taper behavior is unchanged, including personal evidence. High-speed
charging, unknown models, stale readings, unknown charger families and background
connection policy are unchanged. No new scooter command or automatic charging
reschedule is introduced.

A 100% charge limit uses the same corrected duration. Lower limits keep their
existing percentage-based/reference/personal calculation, capped at time to full
for consistency. They are not claimed to have a new voltage-calibrated cutoff
curve. Unknown limits remain unknown and reached limits remain zero. The updated
full duration feeds existing consumers, including a newly enabled Charge done by
schedule; already saved scooter schedules are not continually recalculated.

New charge samples record estimator v4 and their actual source. Historical
predictions and recordings are never rewritten.

## Retrospective recording replay

Fixtures contain only relative times, electrical measurements, SOC, continuity
flags and anonymous saved predictions. No profile identity, location, date,
account data or authentication values are included. Earlier recordings alone
supply personal history when each later charge is replayed. Both accidentally
split warm files are ONE physical charge; the unobserved split is retained as a
gap, and their restarted monotonic clocks are never directly subtracted.

A frozen v3 replay reproduces all 1,833 warm saved predictions exactly, including
runtime reference grace and previous personal-curve precedence.

| Recording / scope | v3 MAE | v4 MAE | Notes |
|---|---:|---:|---|
| Warm charge, all 1,832 pre-completion samples | 27.600 min | 12.892 min | Includes unsupported early voltage range |
| Warm charge, 794 matched samples | 36.118 min | 2.183 min | 43.3% of entire charge; 68.2% of second part |
| Earlier standard finish, all 337 samples | 2.374 min | 2.269 min | Calibration regression, not independent evidence |
| Earlier standard bulk/finish, all 1,070 samples | 3.956 min | 1.133 min | Calibration regression, not independent evidence |
| Earlier complete high-speed charge, 532 samples | 0.407 min | 0.407 min | Exact unchanged outputs |
| Later high-speed charge, 172 samples | 1.349 min | 1.349 min | Includes isolated pre-gap sample with large error |

All high-speed and standard 89%+ outputs are exactly unchanged across full and
80%/95%/unknown target replays. The later high-speed recording has a long gap;
its continuous finishing suffix alone has 0.878-minute MAE, unchanged.

The matched warm subset still has a 10.19-minute 95th-percentile absolute error
and a 38.95-minute maximum near conservative entry blending. It is not uniformly
a two-minute prediction. The approach was selected after inspecting this charge:
this is retrospective validation, not a prospective accuracy guarantee. A fresh
physical charge is still needed to establish behavior beyond these recordings.
Completion means first observed displayed 100%, not charger shutoff or balancing.

## Reproducible regression checks

`bash tests/run.sh` includes:

- `StockVoltageReferenceTest`: admission, target ordering, provenance, stale and
  mismatched electrical frames, unknown model/charger, voltage/current boundary
  sweeps, and unchanged later finishing scope.
- `StockVoltageReplayTest`: all 3,948 fixture samples, prior-only personal history,
  split/gap integrity, unknown cutoff handling, full/limit consistency and warm
  accuracy/coverage bounds. Unmatched warm predictions retain the recorded v3
  values exactly.
- Existing standard/fast reference, personal history, recovery, capture metadata,
  charge schedule controller and taper suites.

The full baseline/current per-sample replay and provenance reports remain local
validation evidence outside the source repository. No physical charging or scooter
settings are changed by these tests.
