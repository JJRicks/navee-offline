# Standard-charger pack-model estimates

Estimator `ut5-stock-pack-v6`, following [conservative startup estimates](stock-startup-estimates.md).
It replaces UT5 MAX standard-charger timing **below 89%** only. From 89% the existing finishing
reference, the 99% taper matcher and personal finishing curves are unchanged, and the high-speed,
unknown-charger and other-model paths are untouched.

## What the recorded charges show

The five completed standard charges in the [public evidence bundle](data/stock-startup-v5/README.md)
come from two scooters, starting at 1%, 22%, 51%, 60% and 88%. Two physical effects explain most of
the earlier estimators' error.

**1. The battery percentage is not a reliable progress measure near the top.** Below about 80% the gauge
counts charge at exactly the rated capacity, 15.3 Ah per 100%. When pack voltage reaches about 53.0 V
it then catches up toward 89% within a few minutes, and the size of that catch-up varies. The same 70%
reading left between 2.3 and 3.5 Ah to deliver before 99%, depending on the charge. Pack voltage at
the same point agreed across both scooters: about 1.55-1.61 Ah remained near 52.3 V. The catch-up
grows with discharge depth. Among the four lower-start charges, the gauge-equivalent 99% point fell
from about 92% (60% start) to about 85% (1% start). Measured from the start of the 99% stage, the
later milestones are regular:

| Milestone | Minutes before the gauge first shows 99% |
|---|---:|
| Pack voltage reaches 53.0 V | 24-27 |
| Gauge first shows 89-90% (after a catch-up) | 16-18 |
| Gauge first shows 95% | 14-16 |
| Gauge first shows 98% | about 2 |
| 99% to first displayed 100% | 26-32 (mean 29.8) |

**2. The standard charger's current falls during the first hour.** One curve fits all five charges:
`I(age) = I_settled * (1 + K e^(-age / 25 min))`, with an rms error of 0.8%. Here `age` is the time
since charging began and `K` is the excess current at plug-in:

| Minutes since charging began | 0-5 | 5-15 | 15-30 | 30-60 | 120+ |
|---|---:|---:|---:|---:|---:|
| Scooter A median current | 2.29 A | 2.21 A | 2.07 A | 2.00 A | 1.94 A |
| Scooter B current | 2.46 A | 2.39 A | 2.28 A | 2.21 A | 2.17-2.19 A |

The live current at plug-in overstates the settled charge rate by about 14-19% (Scooter A's charger about 19%, Scooter B's about 14%). This is why the earlier 80%
estimates were up to 38-42 minutes late from a low start. The earlier full-charge estimates partly hid
the same error: their rated-Ah bulk time also ignored the late catch-up, and the two errors largely
cancelled.

## The model

Below 89%, with an admitted standard charger:

1. **Charge still needed before 99%.**
   - **Pack-voltage estimate:**
     - Compensate the voltage for live current: `V - 0.2 Ω * (I - 1.94 A)`.
     - Look it up in a non-increasing table fitted from all five charges by isotonic regression.
   - **Gauge estimate:** rated Ah multiplied by the distance from the current percentage to the 99%
     point expected for this discharge depth.
   - **Blend:** the two are combined by their uncertainties.
     - Voltage counts for less where the table is steep or near its ends, and below 48.5 V, where the
       two packs differ most.
     - The gauge is ignored once voltage passes 53.3 V, because its catch-up has begun.
   - **Plausibility check:** a voltage/percentage pair that disagrees by more than a quarter of the
     pack keeps the earlier estimate. The largest real disagreement was 1.0 Ah.
2. **Time.**
   - Deliver that charge while the current decays toward its settled level, given the charge's age.
   - Add the observed 99%-to-100% finish.
3. **Charge limits below 99%.** The gauge reaches a limit by steady counting or by its catch-up,
   whichever comes first. Both paths rise with the limit, so a higher limit never arrives sooner.
4. **Charge age.** `ChargeProgressTracker` records when it sees charging begin after a fresh
   not-charging state.
   - **Start not seen:** the charge is assumed to have just begun, the cautious case.
   - **After ten minutes of watching:** the shape of the current identifies how far its warm-up had
     progressed.

**Display and planning.**
- **Display:** the dashboard shows the most likely time.
- **Planning:** Charge done by uses a cautious time that assumes:
  - a catch-up larger than the fitted trend by the largest deviation observed, plus 1%;
  - the strongest current decay observed, plus 0.02;
  - the longest finish observed, plus 2 minutes;
  - the latest milestones observed.
- **Buffer:** the existing twenty-minute Charge done by buffer is still added on top.

**Personal history.** Percentage-only personal history no longer changes bulk-stage timing. The
varying gauge catch-up is what made shorter personal history unsafe in v4. Personal finishing curves
still apply from 89%.

## Results

Every figure below comes from `fit_pack_model.py` and is committed in
[evaluation.json](data/stock-pack-v6/evaluation.json).

**Method: leave one charge out.** Each charge is predicted by constants refitted without that charge.
This includes the voltage table, catch-up, decay, finish and milestones. Errors are predicted minus
observed minutes over all 5,129 points below 89% (4,590 for the 80% limit). The v5 column replays the
released v5 over the same points. Its references were calibrated on some of these charges, so its
in-sample charges flatter it.

| Limit | v6 display MAE | v5 MAE | v6 display worst late | v5 worst late | v6 planning worst late | Points late beyond the 20-minute buffer, v6 planning / v5 |
|---|---:|---:|---:|---:|---:|---:|
| 100% | 2.9 | 17.4 | 9.5 | 4.2 | **none** (worst +0.2) | 0 / 0 |
| 95% | 2.6 | 26.6 | 6.5 | 11.5 | 2.1 | 0 / 0 |
| 80% | 2.2 | 3.5 | 5.7 | 42.2 | 2.1 | 0 / **158** |

**Average error by charge.** Display time to 100%, each charge held out:

| Charge | v6 | v5 |
|---|---:|---:|
| A, 88% start | 4.0 | 0.1 (v5's own calibration trace) |
| A, 60% start | 6.3 | 0.1 (v5's own calibration trace) |
| A, 51% start | 1.7 | 2.7 |
| A, 22% start, warm | 2.7 | 19.9 |
| B, 1% start | 2.2 | 30.7 |

**First estimates**, minutes, + = too long. v6 is held out; v5 is as released:

| Charge | 100%, v6 / v5 | 95%, v6 / v5 | 80%, v6 / v5 |
|---|---:|---:|---:|
| A, 88% start | +5.8 / -0.9 | +0.7 / -5.1 | - |
| A, 60% start | -5.3 / -3.2 | -2.3 / -10.0 | +0.4 / -8.9 |
| A, 51% start | +4.3 / +2.2 | +6.7 / -5.1 | +0.4 / -14.7 |
| A, 22% start | +5.9 / +2.2 | +6.5 / -6.5 | -3.3 / -37.6 |
| B, 1% start | +22.9 / +11.0 | +21.0 / +0.2 | +20.8 / -31.7 |

Scooter B's held-out first estimate is the weakest case. With B excluded, only Scooter A's charger
decay is known: about 19% above settled at plug-in, against about 14% for B's charger. The estimate
corrects itself within the charge; B's whole-charge display MAE is 2.2 minutes.

**App opened after charging started.** The app first sees an already-running charge at sampled
points below 85%:

| | Display MAE 100% / 95% / 80% | Planning worst late |
|---|---|---|
| At first sight (assumes a fresh charge) | 22.3 / 22.5 / 16.9 | never late (+3.9 / +1.6 / +0.6) |
| After 10 minutes of watching the current | 5.4 / 4.9 / 2.5 | 1.2 / 0.8 / 4.3 |

**Handover at 89%.** On the five charges, the change at the step into the existing finishing reference
is between -3.6 and +4.7 minutes. Most of it is that reference's own bias. Its 89-90% values come from
an 88%-start trace without a catch-up, so for typical charges they run 2-5 minutes long. Recalibrating
it is a possible follow-up, outside this change.

**Charge done by.**
- The plan's start is the deadline minus the cautious estimate minus twenty minutes.
- In these replays no plan for 100%, 95% or 80% started too late to finish by the deadline.
- Full-charge plans can finish up to about an hour early, worst case 39.75 + 20 minutes.

## Limits

- **Small data:** five completed standard charges from two scooters and two chargers. The
  leave-one-out results test generalization within that set. They are not a population accuracy
  claim, and no prospective charge has been scheduled with v6.
- **Rated capacity:** only reported capacities within 10% of 15.3 Ah are admitted. Low or high
  current (below 1 A or above 3 A), voltages outside 44-54.5 V, contradictory readings and the
  high-speed charger all keep earlier estimators.
- **Charge age:** age comes from the phone's observation. A charger swapped while the phone was away
  restarts the warm-up without the app knowing.
- **Temperature:** temperature, pack aging and other chargers can change the voltage table, the
  catch-up and the decay. The warm charge is included, but no cold charge is.

## More data that would help most

- **More completed standard charges, recorded from plug-in to 100%** (Developer options → Charge
  recording), especially:
  - other chargers, because the warm-up amplitude differs between the two seen so far;
  - different start levels, to firm up how catch-up size depends on discharge depth;
  - a cold pack.
- **The same recordings would also let Volt learn each scooter's own decay and catch-up** from its
  completed charges, rather than only bundled values. That's a natural next step once more owners
  record charges.

## Reproduce

```sh
python3 docs/validation/data/stock-pack-v6/fit_pack_model.py --check
```

The script uses only the Python standard library and the anonymized inputs already published for v5. It fits
every constant, regenerates `Ut5MaxStockPackModel`'s table and `tests/StockPackParity.java`, and
recomputes `evaluation.json`. `--check` fails if any of them drift. `Ut5MaxStockPackModelTest`
verifies that the Java model reproduces the Python model to within 1e-6 minutes on 1,040 cases,
along with continuity, cutoff-ordering and domain invariants. CI runs both checks.
