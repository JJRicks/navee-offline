# Observed charging finishes

Updated October 16, 2026: paired-voltage taper matching, a second reference finish,
continuous-suffix learning and simplified dashboard presentation.
This supplements [charge recording](charge-capture.md) and
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
the first implementation used that one complete trace for replay validation. The
old rated-Ah/current estimate was about 21 minutes at 95%,
versus 41 minutes actually remaining, and about 4.5 minutes at the start of
99%, versus 26 minutes remaining. The old fallback already assumed constant
current: it contained no early-taper penalty to remove.

A second stock-charge recording covered 60–100% in 179.54 minutes. Its first
saved estimate was 167.03 minutes. Mean absolute prediction error before completion
was 4.64 minutes; the app used the bundled reference below 89%, then its earlier
personal finish. It spent 31.57 minutes at 99%, with lower initial plateau power
and a slower taper than the first capture. At the first 99% sample, the saved
personal estimate was 18.22 minutes. Power alone mistook a lower-current early
plateau for a later point in the old taper. This motivates checking voltage and
power together instead of treating a nominal charger wattage as fixed.

The experimental default for every recognized UT5 MAX (product ID 2538) now
uses compact relative SOC boundaries and paired voltage/power taper knots from
both observed 89–100% finishes. No personal recordings, manual charger choice or Developer options
are needed. It does not assume a constant 100 W or replace live power with a
nominal wattage. The public reference and regression fixture contain relative
time, SOC, voltage and current only; dates, profile identifiers and other personal
metadata are absent.

## Bundled default

- A recognized UT5 MAX with no charger selection, or a Stock selection, uses the
  reference only when measured power is plausible. Explicit High-speed selection
  disables this default; unknown models retain the live current-rate estimate.
  The manual choice is available under **Scooter → Battery → Charger for estimates**
  without Developer options, including offline; it is saved per scooter and
  cannot change during an active capture. It sends no scooter command.
- Below 89%, bulk duration uses the reported rated Ah, missing percentage and
  live charging amps up to 89%, followed by compatible observed 89–100% finishes.
  The new capture provides evidence across 60–88%, but that bulk portion remains
  a live-current model rather than a fixed recorded-time table. Initial current
  fell from 2.30 A toward 1.94 A, which the first instantaneous estimate cannot
  know in advance. Bulk power must be within 15% of the recorded
  approximately 95–121.5 W envelope. This does not detect charger hardware.
- From 89% onward, reference durations preserve the gauge's nonlinear progress.
  Current power must fit the recorded range at that SOC within 15%. Short
  flat-power SOC intervals use their first observed arrival boundary, the same
  boundary used by bulk time and cutoff calculations. The expected finishing
  time is included throughout bulk charging, and reaching 89% does not switch
  abruptly to a different phase assumption. Integer gauge updates still cause
  natural steps; no arbitrary animation or timed countdown is invented. Each
  compatible reference keeps its own boundaries and cutoff subtraction before
  their estimates are averaged. During the long 99% plateau, approximately
  one-minute paired-voltage/power knots locate the approximate stage.
- Taper matching first keeps points for which live power is within 15% of recorded power,
  then averages those within 0.01 V of the nearest voltage match. A nearest match
  farther than 0.10 V or candidate remaining-time spread over ten minutes is
  rejected. Both live voltage and current must come from the same fresh frame.
  This avoids confusing similarly powered states at different pack voltages;
  it does not identify charger hardware or extrapolate an unseen voltage range.
- On a live authenticated connection, brief power fluctuations at a matching
  boundary do not immediately switch back to the live-rate formula. Entry still
  requires the usual 15% envelope; an already matching reference may remain
  within 20% for at most two distinct out-of-band electrical frames and less
  than 15 seconds. The third miss, time limit or wider mismatch returns to a
  freshly calculated fallback. Repeated widget refreshes of the same frame do
  not count as extra misses. No ETA or old electrical reading is held over.
  Disconnects, stale/non-charging data, simulation, profile/charger changes and
  backward clocks clear the allowance. Stateless calculations use strict bounds. The admitted 20% grace also applies
  to the taper matcher; its voltage and ambiguity checks remain unchanged.
- A lower cutoff includes only time up to that target. Cutoffs at or below 89%
  keep the rated-Ah/current estimate; higher cutoffs subtract the recorded
  remaining duration at that boundary. Unknown cutoffs remain unknown.
- Fresh charging state, SOC, rated Ah and paired voltage/current are required
  before any reference calculation. The experimental ride-energy estimate is
  not a capacity multiplier. Two captures and modeled bulk time do not
  establish accuracy for every scooter, temperature or charger.
- Result source flags distinguish the bundled reference from personal learning.
  The default reports zero learned personal sessions. Personal estimates are
  computed separately from the original live-rate baseline, then replace only
  the targets they actually cover; missing personal evidence cannot erase or
  relabel an otherwise applicable default.

## Personal refinement eligibility

- A finishing trajectory requires a `COMPLETE` recording with an actual final
  100% sample. Its retained, continuous final segment must contain at least
  61 samples, span at least ten SOC percentage points and last at least ten
  minutes. Earlier samples cannot satisfy these minimums for a shorter suffix.
- Gaps, samples more than 25 seconds apart, non-increasing sample times, wall-clock
  discontinuities greater than two seconds, SOC reversals/skips, fractional SOC
  and absent charging power reset the candidate segment. Only the uninterrupted
  suffix after the last such break can qualify. It begins at the first later
  valid observed SOC rise, excluding the partial starting percentage interval.
  For example, gaps at 62% and 70% can leave usable 71–100% evidence, provided
  that suffix meets all three minimums. No missing interval or earlier segment
  is joined into it.
- A break near the finish can leave too little qualifying data and still rejects
  personal finish learning. A gap before the terminal 100% sample cannot establish
  an observed finish. A continuous final zero-current 100% packet remains valid.
  The existing recording and bad-frame gates remain in force.
- Real short percentage transitions remain usable. This separate trajectory
  does not relax the older two-session per-bin timing model or recording gates.
  A recovered suffix cannot qualify earlier timing/power bins when the whole
  recording fails their original coverage requirement. Uninterrupted complete
  finishes retain their earlier per-bin eligibility, including rapid SOC changes.
- Live charging state, SOC, rated Ah and paired voltage/current must still pass
  the existing freshness and validity checks. Matching uses the same scooter,
  manually chosen charger and SOC, with live power within 15% of that interval's
  observed range. This is a plausibility check, not charger identification.
- A substantial observed power rebound rejects stage matching. With a clear
  downward taper, production estimates use the same paired-voltage/power matcher
  as the bundled reference, allowing the estimate to decrease while the gauge
  remains at 99%. Ambiguous candidate stages spanning more than ten minutes
  are refused. Nearly flat power
  uses the SOC interval's midpoint only for intervals no longer than ten minutes.
- Diagnostic status calls one matching complete finish provisional. Multiple compatible
  finishes are averaged with the existing 2.5× disagreement/outlier guard.
  The finishing trajectory takes precedence over older SOC-only averages so a
  second recording cannot bring back a frozen 99% countdown.
- Personal finishing trajectories cover displayed 100% only. Lower cutoffs retain
  their existing two-session curves or applicable default. Unknown cutoffs stay unknown;
  a full-charge ETA is explicitly hypothetical when a lower cutoff is selected.
  Personal finishing trajectories are not extrapolated below their observed range.

This is an approximate replay-based model. Temperature, charger changes, gauge
recalibration and aging can change future behavior. A single recording supports
an experiment, not a demonstrated accuracy guarantee on future charges.
The estimator identifier is `ut5-reference-personal-v2`; future samples save
that version. Existing recordings retain their original predictions unchanged.

## Dashboard estimate presentation

The charging hero, battery widget and charging widget show the estimated time
without the `stock reference`, `learned` or `current rate` source badge. The
separate source explanation and manual charger row are removed from these
dashboard views. Charging power, the configured target, unknown-cutoff wording,
unavailable-reading states and the low-current warning remain visible. Detailed
estimate status remains available through the time tile's tooltip and accessibility
description; estimate source metadata remains available to recording and analysis.

Charger selection remains under **Scooter → Battery → Charger for estimates** and
in the charge-recording flow. The dashboard cleanup does not alter the selected
charger or its per-scooter storage.

## Capacity choice

The default current-rate fallback continues to use the scooter's reported rated
Ah. The experimental ride widget estimates an SOC-based energy equivalent in Wh,
not measured physical pack capacity; voltage window, load and gauge behavior can
bias it. It is not converted into an Ah multiplier for charging.

The new recording's complete 65–85% interval integrated about 3.067 Ah, equivalent
to 15.335 Ah per full gauge span. Earlier complete mid-SOC intervals imply
approximately 15.34–15.37 Ah
per 100 percentage points when integrating charging current across those
boundaries. That supports retaining the UT5 MAX's 15.3 Ah input, but a gauge-derived
partial-cycle equivalent is not an independent pack-capacity test. The latest
near-full gauge jumps and long 99% plateau make a constant Wh-per-percent
capacity calculation especially unreliable. Terminal timing is learned directly
instead of reducing rated capacity to compensate for those effects.

## Validation

`CompletedChargeEstimateTest` replays the deidentified observed trace through
the real recorder/parser and estimator. It checks the roughly 41-minute finish
at 95%, progression during the 99% taper, short SOC transitions, single-session
labeling, and precedence after a second completed capture. Suffix regressions
cover early versus late gaps and clock jumps, two gaps in one completed charge,
SOC reversal resets, a gap at the terminal sample, too few retained samples and
the exact 61-sample/ten-point/ten-minute minimum. They verify that a valid suffix
survives without gaining coverage across the discarded prefix. Two sparse
recordings cannot create pre-gap legacy bins; an uninterrupted finish with only
nine eligible legacy bins retains its prior lower-cutoff evidence. These are replay
checks using the same evidence that trains the model, not independent prediction
accuracy measurements.

`StockChargeReferenceTest` verifies first-use estimates with no recordings and
no charger selection, the known-model and power gates, explicit High-speed
refusal, modeled bulk time, lower/unknown cutoffs and progress during the 99%
taper. It also exercises personal/default source composition: missing,
insufficient or power-incompatible personal evidence preserves the default;
qualified personal evidence replaces only its supported targets. These remain
replay/behavior checks, not an independent prediction trial.
Boundary checks verify continuity as bulk reaches 89% and nondecreasing time
for later cutoffs, including fractional targets around that transition.

`ChargeReferenceGateTest` exercises the reported 95/94.5 W and 93/92.5 W
boundary fluctuations, repeated redraws, distinct-frame and elapsed-time expiry,
large mismatches, context/clock resets and stale/non-charging input rejection.
Source flags and lower/unknown cutoffs remain independent of the runtime gate.
A 99% / 54.387 V regression verifies that a 32→31 W fluctuation preserves the
reference for two distinct grace frames, then returns to a fresh fallback.

Negative cases cover stale/zero electrical data, incompatible live power,
unobserved SOC, lower/unknown cutoffs, incomplete stops, missing 100%, late gaps,
late clock jumps, SOC rollback/skips with insufficient remaining coverage,
insufficient suffix sample count or SOC span, ambiguous power rebounds,
long flat plateaus and scooter/charger isolation. Existing charging, recording,
retention and recovery tests remain in the full pure-Java suite. An adjacent
regression also verifies that a learned full-charge ETA with unknown cutoff
preserves null rather than throwing during numeric unboxing.

The original October 15 verification passed all 28 pure-Java suites (60,819 checks/assertions),
the app and instrumentation builds, and 160 native charge-capture assertions
on each of two disposable emulator configurations: 344dp dark/default font and
725dp light/1.4× font. The service fixture verifies first-use default
estimates with no recordings, manual charger selection or Developer setup,
taper progression, unknown-model/High-speed fallback and stale-data rejection.
It also verifies runtime-gate resets on disconnect, charger/preference changes
and simulation, plus ordinary charger selection, per-profile isolation and
capture guards without any scooter commands. Both selector sheets were visually
checked for fit and legibility.
Bluetooth remains disabled in this fixture; no physical charging session or
future-charge prediction accuracy was tested.

October 16 validation of this change:

- The full pure-Java suite passed; after strengthening the final continuous-bin
  fixture, its affected suite was rerun. Combined total: 80,598 checks/assertions
  across 32 suites, including 9,080 completed-charge, 563 taper-matching and
  151 runtime-gate checks.
- `ChargeTaperMatcherTest` holds each terminal capture out of the other reference.
  Mean absolute 99% error improves from 4.54 to 2.34 minutes in one direction,
  and from 3.71 to 2.21 minutes in the other. These are two captured sessions,
  not a prospective trial or broad model-population validation.
- Exact Java replay of the standalone default on the latest full session improves
  mean absolute error from 4.47 to 3.32 minutes; at 99%, from 4.61 to 1.18 minutes.
  The new default includes that session in calibration, so these are in-sample
  results. The first 60% estimate remains about thirteen minutes early as live
  current subsequently falls; no claim is made that all early-charge error is fixed.
- App and separate native runner builds pass signature and alignment checks.
  Charge-capture validation passes 174 assertions each at 344×882 dp dark/normal
  system font and 725×604 dp light/140% system font, including first-use default,
  hidden source badges, low-current/unknown-cutoff handling and recovery guards.
- Battery/dashboard checks pass 81 assertions at 344×882 dp dark/normal app text
  and 78 at 725×604 dp light/140% app text. Saved-layout regressions also pass.
  The narrow charging hero was visually checked with power and ETA visible and
  no source/charger row. No physical scooter commands are used by these fixtures.
- Independent GPT-6 Astra Ultra and GPT-6.1 Sol Ultra reviews completed. Their
  findings about runtime grace and recovered-prefix legacy eligibility were
  fixed and checked independently. Private exports, absolute recording times,
  scooter identifiers and analysis artifacts are not part of the repository patch.

Existing recordings keep their saved predictions. These changes have not yet
been evaluated during a new physical charging session.
