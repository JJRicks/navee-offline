# Automatic charger selection and UT5 MAX fast estimates

## Behavior

`ut5-dual-charger-v3` adds a provisional high-speed default and a runtime charger
classifier. Neither requires the owner to make recordings. It changes predictions
only and sends no scooter commands. PID 2538 selects these UT5 MAX defaults; other
models retain the live-rate fallback, with compatible manually selected personal
curves still available as before.

The per-scooter estimate selector is Auto / Stock / High-speed. An absent preference
means Auto; an existing explicit Stock or High-speed choice is preserved. Clearing
an override resets runtime classification. Simulation never supplies automatic
evidence. Its manually selected reference preview remains available.

## Selection and continuity

`ChargerDetection` consumes distinct paired voltage/current frames from a real,
authenticated connection. Three frames spanning at least four seconds establish
a candidate. Re-rendering one frame cannot advance confirmation. Frames may be up
to 15 seconds old, matching display freshness and the supported 2–10 second battery
polling interval. Charging recordings retain their separate five-second capture
freshness rule.

- Fast evidence: greater than 150 W and at least 2.8 A, within plausible bounds
  of 12 A, 650 W and 35–60 V.
- Stock evidence: 65–135 W, 1.4–2.5 A, and no more than 85% battery. This is
  deliberately conservative; low power late in a fast charge is ambiguous.
- A confident selection survives the normal falling-current taper. Contrary bulk
  readings invalidate it immediately and require a new confirmation sequence.
- Charging ending, invalid/stale inputs, backwards clocks, lost readiness,
  profile changes and reconnects reset inference. Returning after background
  polling stopped providing fresh electrical readings can therefore leave Auto
  uncertain near full. An override is available; stale readings never become a
  new prediction.

This is a charger-family inference, not a decoded hardware ID. The defaults also
check their own SOC/power/voltage compatibility after selection. Unknown means
live-rate fallback; null no longer silently selects the stock curve. No inference
is persisted or fed back as a verified training label.

## High-speed evidence and model

One uninterrupted owner-provided session contains 533 samples, roughly ten seconds
apart, from displayed 16% to first displayed 100% in 89.13243 minutes. It has no
internal gaps. Current settles around 10.09 A, peak battery-side power is 539.26 W,
taper begins around 72%, and the gauge spends 12.02587 minutes at 99%. Measured
battery temperature spans 20–35°C. These are battery-side readings, not wall power.

`Ut5MaxFastChargeReference` uses rated Ah and live current before 72%, appending
the observed finishing duration from the beginning. At and above 72%, first-arrival
SOC boundaries preserve the rapid 81–90% gauge progression. Paired power/voltage
locate progress during the long 99% plateau. Fractional SOC and cutoffs interpolate
the same boundaries; a lower target excludes the later part of the curve.
The bulk/taper boundary is continuous. Below 16% the bulk estimate is modeled,
not measured. No ride-derived capacity correction is applied.

The sanitized calibration fixture contains only relative elapsed minutes, SOC,
voltage, positive current magnitude and positive power magnitude. It excludes
session/profile identifiers, wall-clock timestamps, locations and credentials.
The original export stays outside the repository. A narrow ignore exception
permits this one fixture while ordinary recording exports remain ignored.

Replaying this session checks implementation fit, **not independent accuracy**.
A second uninterrupted fast charge is the next useful holdout. The target is first
displayed 100%; continued balancing and charger shutoff are not established by
this recording. Tail overlap makes cold late-stage charger identification uncertain.

## Personal learning and saved estimates

Matching manually labeled personal curves take precedence separately for full
charge and the configured limit. An unsupported personal target retains its
matching bundled reference. Incompatible or unavailable evidence falls back to
the fresh live-rate estimate. Recording confirmation still asks for a physical
Stock / High-speed label. That label temporarily selects active capture estimates
without changing the owner's saved Auto/manual preference.

Saved predictions carry estimator version `ut5-dual-charger-v3` and per-target
sources, including the additive `FAST_REFERENCE` value. The journal remains v2
and export remains `navee-charge-recordings-v1`; historical predictions are never
recomputed. Older readers that do not know the new source can discard the optional
prediction metadata while preserving original electrical samples. The export's
manual charger-selection label remains accurate because recording labels are
still explicitly confirmed by the owner.

## Validation

The core suite covers detection at ordinary and slow polling rates, duplicate
frames, stale/gapped readings, scope resets, contradictory bulk power, ambiguous
late starts, model/charger isolation, per-target personal/default precedence,
saved provenance round trips, fractional targets and full calibration replay.
Android validation covers the three-option controls, persisted overrides, Auto
reset, profile isolation, simulation, capture-label isolation and default source
selection. No live scooter commands or physical charging tests are required by
these suites.
