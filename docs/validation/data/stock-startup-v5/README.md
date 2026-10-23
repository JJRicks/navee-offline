# Standard-charger v5 replay evidence

This bundle contains anonymized measured inputs and an offline replay of released
`ut5-stock-voltage-v4` against candidate `ut5-stock-startup-v5`. It lets a reviewer
inspect the evidence and run the actual Java estimator without a phone, scooter,
Android SDK, account, network connection, or original private exports.

The candidate improves the conservative first full-charge prediction on this small
dataset. It does **not** improve average error on every charge. See the regressions
and incomplete-recording caveats below before drawing conclusions.

![Predicted finish minus observed finish, throughout each completed charge](finish-error.png)

## Run it

Requirements: Python 3.8+ standard library, Git with the baseline commit available,
and JDK 11+ (`java` and `javac` on PATH). Run from the repository root:

```sh
python3 docs/validation/data/stock-startup-v5/rebuild_paired_input.py
python3 docs/validation/data/stock-startup-v5/replay.py --out /tmp/volt-stock-v5-replay
```

Optional: regenerate the chart with Matplotlib installed (not needed for replay):

```sh
python3 docs/validation/data/stock-startup-v5/plot.py /tmp/volt-stock-v5-replay --out /tmp/volt-charge-plots
```

The first command verifies all paired Scooter B samples against its anonymized
state events. The second compiles the production estimator twice and writes:

- `scooter-a-baseline.csv`, `scooter-a-candidate.csv`: all 5,618 observed samples in
  both bundled-only and preceding-personal-history modes, or 11,236 rows per file.
- `scooter-b-baseline.csv`, `scooter-b-candidate.csv`: 2,077 paired replay rows per
  file, including the first displayed 100%; no personal history.
- `summary.json`: initial estimates, whole-session and phase errors, source hashes,
  and the results of the regression checks.

Baseline is frozen at `0e427a1e5eae004662d49ecc462d6c81eb3b9b67`. The candidate
defaults to `HEAD`; `--candidate-ref 521b4c7e54aa8b16bc34bd9694f843cb342d0ddc`
replays the original candidate after later app development. The script checks
the production source hashes and exact metrics against
[expected-results.json](expected-results.json); later source changes deliberately
fail that check. A shallow clone may need the baseline and candidate commits
fetched first. Generated builds and anonymous learning stores use temporary
directories and are removed automatically.

`expected-results.json` is a committed, machine-readable result snapshot, so a
reviewer can examine both modes and all phases without running Java. It was
derived from the original private replay, then independently reproduced using
this portable bundle. [manifest.json](manifest.json) records fixture counts and
SHA-256 hashes. Raw exports are not required or published.

## What is included

Scooter A is one UT5 MAX with ten Volt journals, kept in original chronological
order. Scooter B is another UT5 MAX using a standard charger from about 1%.

| Scooter A journal | Charger | Observed SOC | Samples | Finish comparison |
|---|---|---:|---:|---|
| 0 | Standard | 23–29% | 167 | Incomplete; no observed finish |
| 1 | Standard | 29–38% | 227 | Incomplete; no observed finish |
| 2 | Standard | 88–100% | 338 | Complete |
| 3 | Standard | 60–100% | 1,071 | Complete |
| 4 | High speed | 16–100% | 533 | Complete |
| 5 | High speed | 84–86% | 22 | Incomplete; no observed finish |
| 6 | High speed | 73–100% | 173 | Complete, with a 14m42s gap |
| 7 | Standard, warm pack | 22–46% | 668 | First part of the same physical charge as 8 |
| 8 | Standard, warm pack | 50–100% | 1,165 | Continuation after an 18m35s gap |
| 9 | Standard | 51–100% | 1,254 | Complete |

There are seven completed physical charges across both scooters: five standard
and two high speed. Journals 7/8 are one evaluation charge. The three incomplete
journals remain useful for prior-history behavior and regression coverage; they
cannot establish a full-charge finish error.

### Data treatment and privacy

- Profile identifiers, journal names, account/device identifiers, absolute real
  dates/times, original Home Assistant entity IDs, and local file paths are omitted.
  The neutral A/B labels identify separate histories, not different app models.
- Measurements, sample order, relative timings, missing-interval flags, saved ETA
  values/versions, and first-observed threshold truth are preserved. No missing
  power or percentage samples are reconstructed.
- `scooter-a/journal-*.csv` use deliberately synthetic journal clocks, beginning at
  `1800000000000 + journal_index × 100000000` for wall time and `1000000` for
  monotonic time. Within-journal offsets are preserved. These are fake dates;
  gaps between separate synthetic journal origins do not represent real dates.
- `points-*.csv` timing is relative. The split warm charge uses one physical
  wall-time origin, retaining its gap and the intervening monotonic-clock reset.
- Scooter A runtime mode learns only from earlier journals. Journal 7 is excluded
  as prior history while replaying 8, because it is the same evaluation charge.
  Both remain separate preceding journals for 9, matching production storage of
  their disjoint measured intervals. Bundled mode supplies no personal curve.
- Scooter B never receives Scooter A personal history. Some Scooter A recordings
  helped calibrate bundled references. This is retrospective model selection and
  regression checking, not a prospective or population-wide validation set.

### Scooter B state-change reconstruction

`scooter-b/state-events.csv` contains all 19,628 original sensor events with six
generic channel names and time relative to the first charging-on event. Negative
times preserve the preceding stale 100% and the first 1%, without disclosing a
calendar date. `rebuild_paired_input.py` documents and verifies the transformation:

1. Group events within 5 ms of a group's first event to accommodate serialization
   skew. Update each channel's state; hold unchanged states until another event.
2. Exclude the initial stale 100% before charging starts. Evaluate voltage-update
   groups during charging through the first real 100%.
3. Require available battery/voltage/current/power, inward current below −0.05 A,
   and agreement between voltage × current and reported power within 0.2 W.
4. Preserve explicit unavailable intervals as gaps; sample at approximately
   10-second spacing, keeping the first complete sample. Do not interpolate.

This produces the 2,077 rows in `paired-input.csv`. Three unavailable intervals
total about 67 seconds. The 99% plateau lasts about 29.66 minutes. Temperature
units were absent; Fahrenheit is inferred and the converted column is explicitly
labeled. Temperature does not affect this estimator or these replay outputs.

State-change exports are not synchronized raw BLE frames. Replayed samples are
hypothetical fresh paired inputs; this does not prove the app received these same
values together or at the export timestamps. Charger family is supplied from the
known recording type; this bundle does not simulate automatic family detection.

## Results

All times below are minutes. Positive error means the predicted finish is later
than the actual first displayed 100%; negative means it predicts too soon. The
separate **20-minute Charge done by buffer is not included** in the estimates.
The scheduler previously used ten minutes. Published JSON metrics whose names
contain `10min` and the plot's −10-minute reference line intentionally retain that
historical benchmark. They are not twenty-minute metrics. The full-time
estimator's separate up-to-ten-minute startup allowance remains unchanged.
Scooter A uses preceding-personal-history/runtime mode here.

| Charge | First v4 ETA | First v5 ETA | Actual remaining | v5 first error | Whole-charge MAE v4 → v5 |
|---|---:|---:|---:|---:|---:|
| A standard 88% | 54.58 | 55.58 | 56.50 | −0.92 | 2.27 → 2.26 |
| A standard 60% | 166.33 | 176.33 | 179.54 | −3.21 | 1.13 → 1.05 |
| A standard warm 22% | 318.00 | 328.00 | 325.80 | +2.21 | 12.89 → **16.97** |
| A standard 51% | 202.26 | 212.26 | 210.02 | +2.24 | 3.36 → 2.36 |
| B standard 1% | 383.03 | 393.03 | 381.99 | +11.04 | 21.63 → **27.12** |
| A high speed 16% | 92.07 | 92.07 | 89.13 | +2.94 | 0.41 → 0.41 |
| A high speed 73%, gap | 125.18 | 125.18 | 43.25 | **+81.93** | 1.35 → 1.35 |

MAE is the unweighted mean absolute error at observed sample instants before
100%, not a time-weighted interpolation through gaps. The high-speed 73% row's
first low-power frame uses current-rate fallback, then there is a long gap. Its
large first error remains unchanged; the later continuous suffix has about
0.88-minute MAE. The small whole-session mean does not erase that startup failure.

The standard 51% charge improves substantially during its first 30 minutes:
MAE falls 17.29 → 10.57 minutes, with its maximum early optimism falling from
22.61 minutes to zero. However, the warm charge and Scooter B become more
pessimistic throughout much of bulk charging. Their maximum v5 overestimates
are about 50.24 and 50.60 minutes, respectively. This is the deliberate cost of
the conservative fallback, not a universal accuracy improvement.

All five completed standard charges' observed full-charge predictions fit within
even the former 10-minute scheduling buffer. The scheduler now uses twenty
minutes; none of these recorded ETA curves or golden results changed. The worst
remaining full-charge optimism is 8.53 minutes, in the unchanged finishing region.
This retrospective statement assumes charging follows the same measured trajectory after a delay;
it is **not a tested paused-until-start experiment or a delivery guarantee**.

### Known limitation: lower cutoff targets

Every 80% and 95% estimate is numerically unchanged. For low-start charges the
existing 80% estimate can be roughly 37.58 minutes too optimistic on the warm
charge and 42.17 minutes on Scooter B. Even the increased 20-minute buffer cannot
cover those cases. This full-charge refinement must not be claimed to fix every Charge done
by target; a lower-target refinement requires separate calibration and validation.

## Field dictionary

### Scooter A `points-*.csv`

| Fields | Meaning |
|---|---|
| `case`, `session`, `point` | Neutral evaluation group, journal index, sample index; 7/8 share `WARM_PHYSICAL` |
| `elapsed_ms`, `physical_elapsed_ms` | Monotonic offset within journal; wall offset within physical charge |
| `soc`, `volts`, `amps` | Reported battery %, pack V, pack A; charging current is negative |
| `gap` | 1 if a missing interval precedes the sample (also 1 on initial samples) |
| `truth_full`, `truth80`, `truth95` | Retrospective minutes until observed threshold; empty if unknown; zero if reached |
| `saved_full`, `saved_source`, `saved_version` | Original recorded full ETA and provenance when present; not model inputs |
| `temperature_c`, `charger` | Recorded temperature and known charger family |

The journal files use the production `charge-v1` format with synthetic clocks,
generic profile `fixture`, and preserved measurement/gap/stop-reason fields.
Journal power equals measured voltage × current. `StartupReplay.java` loads these
through the production `ChargeSessionStore`, rather than reconstructing its
learning logic in Python.

### Scooter B

`state-events.csv`: `event_elapsed_ms`, generic `channel`, and original `state`.
`paired-input.csv`: relative `elapsed_ms`, `soc`, `volts`, `amps`, reported
`power_w`, `temperature_c_assuming_F`, `gap`, and retrospective threshold truth.

### Generated replay outputs

Scooter A appends `mode`, `qualified_prior_journals`, `full`, `full_source`,
`learned_sessions`, the corresponding full/cutoff estimates for selected 100/80/95
and unknown targets, and `voltage_match`. Scooter B appends `estimate`, `limit80`,
`limit95`, `source`, and `voltage_match`. All ETAs are minutes. Empty means no
estimate, not zero. Model ID 2538 and rated 15.3 Ah are fixed UT5 MAX inputs in
both harnesses; they are not inferred battery-health measurements.

## Verification boundaries

The runner checks all 1,254 originally saved v4 estimates **and sources** against
the frozen baseline. It also checks unchanged fast/89%+ results, unchanged lower
cutoffs, full-time independence from selected target, 100%-target/full agreement,
unknown-target nulls, reached-target zeros, and lower-target/full ordering. Every
published result is compared to committed golden summaries and source hashes.
Neither the future observed finish nor saved estimates enter the estimator.

This bundle exercises the pure estimator and prior-history store. It does not
exercise Bluetooth scheduling writes, lifecycle behavior, charger-family
identification, or UI state; those are covered by the PR's separate controller,
core, and native validations described in [the parent report](../../stock-startup-estimates.md).
