# Measure, then schedule: Charge done by

This follows [standard-charger pack-model estimates](stock-pack-estimates.md) (`ut5-stock-pack-v6`). It changes
when Charge done by arms, not how it estimates or what it sends to the scooter. It also makes a charge
recording survive Volt's own scheduled pause, so the first real scheduled charge can be recorded end
to end.

## Why measure first

The v6 planning estimate needs the charge's age, because the standard charger's current falls during
its first hour. `ChargeProgressTracker` knows the age when it sees charging begin. Otherwise it
assumes the charge has just started, the cautious case, until about ten watched minutes of current let
`Ut5MaxStockPackModel.inferStartAge` place the warm-up. From the leave-one-charge-out replays in
`docs/validation/data/stock-pack-v6/evaluation.json` (app opened after charging had started):

| Plan made | Display MAE 100% / 95% / 80% (min) | Planning worst late, before the 20-minute buffer |
|---|---|---|
| At first sight (fresh charge assumed) | 22.3 / 22.5 / 16.9 | never late (+3.9 / +1.6 / +0.6 early at worst) |
| After 10 minutes of watching | 5.4 / 4.9 / 2.5 | 1.2 / 0.8 / 4.3 min |

So a plan armed after measuring finishes much closer to the chosen time. Its worst replayed lateness,
4.3 minutes at the 80% limit, stays well inside the unchanged twenty-minute buffer.

Arming pauses charging, and a paused charger draws no current to watch. So the order is
**measure, then schedule**, not schedule then refine.

## Design

`ChargeMeasureWindow` is a pure state machine; `ScooterService` drives it from its one-second tick
while active.

- **Offered only when it can help** (`ScooterService.chargeMeasureOffered`):
  - UT5 MAX standard charger below 89%, so the v6 pack model applies;
  - the charge's start was not seen and its age has not been inferred;
  - Charge done by could otherwise arm right now;
  - app notifications are allowed;
  - the cautious plan would not start charging within three minutes.
- **The owner chooses.** Turning on Charge done by asks **Measure this charge first?** The dialog says
  Volt stays connected for up to ten minutes, even in the background when background connection is off,
  with a notification. **Schedule now** keeps the old behaviour. If measuring became impossible while the
  dialog was open, **Measure first** schedules immediately rather than doing nothing.
- **Bounded.** The window lasts at most twelve minutes: ten of inference, plus two 30-second samples of
  slack. It also ends no later than the moment the cautious plan would have had to start charging, so
  arming is never later than the cautious plan. Charging continues throughout the window.
- **Visible and cancellable.**
  - The foreground notification reads "Measuring charge for Charge done by · Sets the schedule within
    N min · keep the charger connected", with a **Schedule now** action.
  - The Charge done by row and tile show **Measuring**. Tapping either offers Keep measuring, Schedule
    now or Stop measuring.
  - Backup and simulation wait for the window to end.
  - The window holds the same wake lock as a recording, so a screen-off phone keeps sampling between
    ticks.
- **Finish time edited while measuring.** The window can only end sooner, never later: its deadline
  becomes the earlier of its own bound and the new cautious start. If the new time is too close to wait,
  measuring ends at once. Charge done by then follows the existing rule: it is not set, and charging
  continues ("Charging now — not enough time to wait before the deadline"). Nothing rolls on to the next
  day's deadline.
- **Outcomes:**

  | Event | Result |
  |---|---|
  | Age known (seen or inferred) and the estimate is available | Arm with the measured planning estimate |
  | Timeout, the cautious start reached, or **Schedule now** | Arm with the cautious fresh-charge estimate, as before this change |
  | Charging stops, the scooter changes, the clock goes backwards, readings go stale or the link is still down at the deadline, Bluetooth permission is removed, Volt closes | Nothing is armed; a notice says why |
  | Owner's **Stop measuring** | Nothing is armed and no notice is posted (explicit choice) |

  Arming reuses `ChargeScheduleController.setDoneEnabled` unchanged: clock, start time and enable, the
  same writes as before. The bounded connection is held until those writes finish. After that, the usual
  background rules apply again. The result is a toast when Volt is open, otherwise a separate
  notification.

## Charge recordings across the scheduled pause

A recording used to end with `CHARGING_ENDED` as soon as charging stopped, including Volt's own
scheduled pause. Now, while a Volt-armed Charge done by plan or an enabled Scheduled charging time lies
ahead (until two minutes after its start), or while a schedule change is in flight, the recording handles
a fresh charging=off reading like this:

- the journal stays open and gains a `p,<wall>,<elapsed>,1` marker; no samples are written;
- fresh readings keep the capture alive, using the same thirty-second staleness rule;
- when charging resumes, a `p,<wall>,<elapsed>,0` marker follows, and the next sample starts a new
  segment (`gap_before`);
- a reconnect that finds charging off during such a pause resumes into the pause instead of ending;
- learned-curve coverage is judged on charging time, so a paused charge can still qualify; bins never
  span the pause;
- export adds `schedule_pauses` with start and end times;
- history rows say "paused N min for the schedule";
- a recording allows at most eight pauses; the eighteen-hour and sample limits are unchanged;
- the schedule can be confirmed off before charging resumes, so an open pause keeps holding for two
  minutes after its plan is cancelled or starts; a charger that stays off after that ends the recording
  as before;
- a recording that ends inside a pause closes it at the last fresh reading, so history shows the real
  paused time.

Older Volt versions do not read `p` lines. Restoring a newer backup into an older version keeps such a
recording as unreadable: **Replace everything** restores it byte for byte, and a merge skips it. Earlier
recordings are unaffected.

## Validation

- **Core suite (`bash tests/run.sh`) passes**, including:
  - `ChargeMeasureWindowTest`, 24 checks: offer threshold; full window versus the cautious-start cap;
    measured versus cautious arming; arming requested once; recovery from a disconnect before the
    deadline; unplugging, stale readings, disconnect at the deadline, scooter change and clock reversal;
    a finish-time edit only shortening the window, never during arming.
  - `ChargeSessionStoreTest`, 562 checks: three-hour pause continuity; heartbeat freshness; exact pause
    duration; gap only on the first resumed sample; curve qualification; export intervals; backup-journal
    validity; the eight-pause limit; a recording that stops inside its pause keeping its length; the
    two-minute hold when a plan is confirmed off before charging resumes; seven malformed marker
    sequences refused.
  - `ChargeRecoveryPolicyTest`, 37 checks: a post-loss charging=off reading resumes into a Volt pause;
    any other state, or no schedule, still ends the capture.
- **Emulator** (disposable, read-only API 36.1 AVD, simulator and fault injection only, no radio, no
  scooter writes beyond the simulator):

  - `run-charge-capture.py` 2/2 (228 assertions each at 344×882 dark/1.0 and 725×604 light/1.4), including the new `verifySchedulePause`:
    - an armed plan before its start counts as Volt's pause, one past its grace or no plan does not;
    - paused status and notification keep the Stop action;
    - heartbeat freshness;
    - resume into the same journal;
    - the exported pause is closed and not bridged.
  - `ChargeScheduleValidation` at 344×882 dark/1.0 and 725×604 light/1.4, 185 assertions each, including `verifyStandaloneMeasureTick` and the new `verifyMeasureWindow`:
    - outside simulation, a standalone window keeps its wake lock through a full service tick;
    - a finish time edited to one too close to wait ends measuring at once and arms nothing;
    - no offer in simulation;
    - offer dialog wording and buttons, and the fallback when measuring cannot start;
    - a 12-minute bound;
    - backup refused while measuring;
    - notification title, bound and Schedule now action;
    - the Measuring row with spinner and no switch;
    - Keep / Schedule now / Stop dialog, where Stop arms nothing;
    - Schedule now arms, the timeout arms, unplugging ends unarmed.
  - `ChargeScheduleWireValidation` 63 assertions (with `hidden_api_policy 1`, reset afterwards).
  - `run-battery-widgets.py` 5/5.
  - `LaunchValidation` with permissions denied and granted (8 checks each).

## The first scheduled charge is the validation run

Nobody has yet run a real scheduled charge end to end: plug in, measure, arm, pause, scheduled start,
100%. With this build:

1. Plug in the standard charger (for the measuring step, before opening Volt).
2. Start **Developer options → Charge recording** (Stock).
3. Turn on Charge done by and choose **Measure first**.

The recording then keeps the measuring minutes, the pause and the resumed charge in one journal.
Exporting it answers the open questions:

- whether the charger's start-up current returns after the pause (v6 errs early either way, because a
  fresh start charges faster);
- how close the measured plan finished to the chosen time.

## Limits

- **Small data.** The improvement figures are retrospective replays of five recorded charges, not a
  prospective test.
- **Charge age is inferred** from one connection. A charger swapped while Volt was away restarts its
  warm-up without the app knowing.
- **Measuring needs a live connection** for its ten minutes. A disconnect that outlasts the window leaves
  Charge done by off (with a notice) rather than arming without fresh readings.
- **The window is held only by Volt's foreground service.** If Android kills it, the closing notice is the
  only trace, as with charge recordings.
