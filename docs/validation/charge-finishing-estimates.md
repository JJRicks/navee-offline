# Observed charging finishes

October 15, 2026. This supplements [charge recording](charge-capture.md) and
[charge recovery](charge-recovery.md); recording and reconnect behavior are unchanged.

## Why the estimate changes

An owner-recorded UT5 MAX stock-charger session covered reported 88–100% in
56.50 minutes with no internal recording gaps. Battery-side power was about
109–121 W before the final taper. It reached reported 99% after 30.29 minutes,
then remained there for 26.21 minutes as power fell to about 37 W. The final
100% packet still reported about 22 W. This is evidence about the displayed
gauge's finish, not proof that cell balancing or all charger activity ended.

The gauge also advanced from 91% to 95% in under two minutes. Assuming each
percentage point represents the same remaining energy therefore cannot model
this finish. The owner reports seeing that rapid rise on other charges too;
only this one complete captured trace is used for the replay validation. The
old rated-Ah/current estimate was about 21 minutes at 95%,
versus 41 minutes actually remaining, and about 4.5 minutes at the start of
99%, versus 26 minutes remaining. The old fallback already assumed constant
current: it contained no early-taper penalty to remove.

This change does not hardcode 100 W, the observed higher power, or a curve for
every UT5 MAX. Each scooter learns only from its own saved recordings under the
manually selected charger label. The public regression fixture contains relative
time, SOC, voltage and current only; dates, profile identifiers and other personal
metadata are absent.

## Eligibility and estimation

- A finishing trajectory requires a `COMPLETE` recording with an actual final
  100% sample, at least 61 samples, at least ten fully observed SOC percentage
  points and ten minutes after the first observed percentage transition.
- The initial partial percentage interval is excluded. Within the retained
  trajectory, gaps, samples more than 25 seconds apart, clock discontinuities,
  SOC reversals/skips and absent charging power exclude the trajectory. A final
  zero-current 100% packet remains valid. No missing duration is interpolated.
- Real short percentage transitions remain usable. This separate trajectory
  does not relax the older two-session per-bin timing model or recording gates.
- Live charging state, SOC, rated Ah and paired voltage/current must still pass
  the existing freshness and validity checks. Matching uses the same scooter,
  manually chosen charger and SOC, with live power within 15% of that interval's
  observed range. This is a plausibility check, not charger identification.
- A substantial observed power rebound rejects power-based progress. With a
  clear downward taper, matching uses nearby recorded power stages, allowing the
  estimate to decrease while the gauge remains at 99%. A stage spanning more
  than ten minutes of ambiguous matching power is refused. Nearly flat power
  uses the SOC interval's midpoint only for intervals no longer than ten minutes.
- One matching complete finish is labeled provisional. Multiple compatible
  finishes are averaged with the existing 2.5× disagreement/outlier guard.
  The finishing trajectory takes precedence over older SOC-only averages so a
  second recording cannot bring back a frozen 99% countdown.
- Finishing estimates cover displayed 100% only. Lower cutoffs retain their
  existing two-session curve/current-rate behavior. Unknown cutoffs stay unknown;
  a full-charge ETA is explicitly hypothetical when a lower cutoff is selected.
  No extrapolation below the observed range is added.

This is an approximate replay-based model. Temperature, charger changes, gauge
recalibration and aging can change future behavior. A single recording supports
an experiment, not a demonstrated accuracy guarantee on future charges.

## Capacity choice

The default current-rate fallback continues to use the scooter's reported rated
Ah. The experimental ride widget estimates an SOC-based energy equivalent in Wh,
not measured physical pack capacity; voltage window, load and gauge behavior can
bias it. It is not converted into an Ah multiplier for charging.

The owner's earlier complete mid-SOC intervals imply approximately 15.34–15.37 Ah
per 100 percentage points when integrating charging current across those
boundaries. That is consistent with the UT5 MAX's 15.3 Ah rating. The latest
near-full gauge jumps and long 99% plateau make a constant Wh-per-percent
capacity calculation especially unreliable. Terminal timing is learned directly
instead of reducing rated capacity to compensate for those effects.

## Validation

`CompletedChargeEstimateTest` replays the deidentified observed trace through
the real recorder/parser and estimator. It checks the roughly 41-minute finish
at 95%, progression during the 99% taper, short SOC transitions, single-session
labeling, and precedence after a second completed capture. These are replay
checks using the same evidence that trains the model, not independent prediction
accuracy measurements.

Negative cases cover stale/zero electrical data, incompatible live power,
unobserved SOC, lower/unknown cutoffs, incomplete stops, missing 100%, gaps,
clock jumps, SOC rollback/skips, insufficient SOC span, ambiguous power rebounds,
long flat plateaus and scooter/charger isolation. Existing charging, recording,
retention and recovery tests remain in the full pure-Java suite. An adjacent
regression also verifies that a learned full-charge ETA with unknown cutoff
preserves null rather than throwing during numeric unboxing.
