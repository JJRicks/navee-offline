# Automatic charger selection and UT5 MAX fast estimates

## Behavior

`ut5-dual-charger-v3` adds a provisional high-speed default and a runtime charger
classifier. Neither requires the owner to make recordings. It changes predictions
only and sends no scooter commands. PID 2538 selects these UT5 MAX defaults; other
models retain the live-rate fallback under Auto; an explicit Stock or High-speed
choice still applies that family's personal curves.

The per-scooter estimate selector is Auto / Stock / High-speed. An absent preference
means Auto; an existing explicit Stock or High-speed choice is preserved. Changing
the preference does not discard runtime classification, which describes the
physical charger. Simulation never supplies automatic evidence and clears it. Its
manually selected reference preview remains available.

Capture labels never drive Auto. A preference saved by a v1.3.1 recording is an
explicit choice and is preserved; new recordings do not change it.

## Selection and continuity

`ChargerDetection` consumes distinct paired voltage/current frames from a real,
authenticated connection. Three frames spanning at least four seconds establish
a candidate. Re-rendering one frame cannot advance confirmation. The service observes
once per telemetry update, using `TelemetryHistory` display freshness (15 seconds for
these readings, covering the supported 2–10 second battery polling interval).
Charging recordings retain their separate five-second capture freshness rule.

- Fast evidence: greater than 150 W and at least 2.8 A, within plausible bounds
  of 12 A, 650 W and 35–60 V.
- Stock evidence: 65–135 W, 1.4–2.5 A, and no more than 85% battery. This is
  deliberately conservative; low power late in a fast charge is ambiguous.
- A confident selection is remembered per scooter profile for the current physical
  charge: through the falling-current taper, across reconnects and transport
  changes, and across isolated unpaired, zero-current, implausible or late frames.
  A charge shaped unlike the references keeps its family; the references reject
  frames outside their own envelopes themselves.
- It is dropped by readings that family's charger cannot produce (stock above
  135 W or 2.5 A; fast at or below 85% with at most 150 W or under 2.8 A) for three
  frames over four seconds, by charging reported off, a battery-level drop of a
  point or more, a model or profile change, or two minutes without a usable frame.
  Sustained readings of the other family then select it; otherwise Auto is uncertain.
- A charger could be swapped while the link is down without charging ever being
  seen off. From 96%, where both families' power overlaps, the first usable frame
  after a lost link keeps the family only if it is fresh evidence for it; otherwise
  Auto is uncertain for the rest of the charge. Below 96% the rules above apply.
- A charge first seen near full, or after more than two minutes without fresh
  readings, can therefore leave Auto uncertain; Volt then uses the live rate. An
  override is available; stale readings never become a new prediction.

This is a charger-family inference, not a decoded hardware ID. The defaults also
check their own SOC/power/voltage compatibility after selection, with the same
bounded grace for one or two out-of-envelope frames in either family. Unknown means
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
The bulk/taper boundary is continuous in both time and admission. The voltage
window is the session's range plus 0.35 V, so a fixed pack or sensor offset of
about 0.3 V still admits every calibration sample (replay MAE 0.408–0.410 min at
±0.2 V and ±0.3 V, against 0.407 min unshifted). Below 72% it widens by 0.25 V per
percent. Power keeps stock-charger readings out before 96%; from 96% to full the
two families overlap and classification decides. Below 16% the bulk estimate is
modeled, not measured. No ride-derived capacity correction is applied.

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
Stock / High-speed label. It pre-selects only an explicit preference; under Auto
nothing is pre-selected and Start waits for a choice. That label temporarily
selects active capture estimates without changing the owner's saved Auto/manual
preference. The settings row names the family in effect and why (recording
label, which must be stopped to change, or detected); the developer card counts only that
family's curves, or says none apply until the charger is known.

Saved predictions carry estimator version `ut5-dual-charger-v3` and per-target
sources, including the additive `FAST_REFERENCE` value. The journal remains v2
and export remains `navee-charge-recordings-v1`; historical predictions are never
recomputed. Older readers that do not know the new source can discard the optional
prediction metadata while preserving original electrical samples. The export's
manual charger-selection label remains accurate because recording labels are
still explicitly confirmed by the owner.

## Validation

The core suite covers detection at ordinary and slow polling rates, duplicate
frames, retention across reconnects and unusable frames, the near-full reconnect
check, charge boundaries, off-reference charge shapes, sustained contrary power, ambiguous late starts, fast-path grace, voltage offsets, model/charger isolation, per-target personal/default precedence,
saved provenance round trips, fractional targets and full calibration replay.
Android validation covers the three-option controls, persisted overrides, Auto
reset, profile isolation, simulation, capture-label isolation (including other
models' Auto), the service keeping a detected family across a lost link, and
default source selection. No live scooter commands or physical charging tests are required by
these suites.
