# Haptics, fold stability and the unfolded More split

This follows the owner's 0.12.0 Pixel feedback:
- the favourites drag haptics were well received, and should become subtle app-wide haptics with a setting that defaults to on;
- More's menu briefly appeared at about a quarter of the unfolded width before jumping to a third;
- half-folding the phone flickered, jumped or replayed the screen, including moving the Scooter page's split onto the crease.

## Behavior

**Haptics (`Haptics.java`)**
- There are four feels:
  - **tick:** a tab, a segmented choice, a Top speed preset, a dragged widget or favourite reaching a new slot, or the lock slider crossing its midpoint;
  - **toggle on/off:** switches and favourite stars;
  - **lift:** picking up a widget or favourite;
  - **confirm:** the scooter reporting the lock state the rider asked for (within 15 s), or a ride actually starting or being saved.
- Ordinary taps and scrolling stay silent, as do programmatic changes. `FeedbackSwitch` reacts only to the rider's taps and committed thumb drags.
- A switch plays the direction the rider flipped it. Scooter settings put the switch back to the reported state until the scooter replies, so reading it after the click would play the opposite direction.
- Rides' Phone sensors rows now pass label taps through `performClick`, like the other switch rows, so tapping the label feels the same as tapping the switch.
- A committed thumb drag counts as one click in the dragged direction, with one haptic. Android commits a drag with `setChecked` and cancels the click. Before this, any switch that acts on clicks only looked changed after a swipe, and the next redraw put it back. That included the new Haptic feedback opt-out, the other App preferences toggles and scooter settings. Taps, cancelled drags and code changes behave as before.
- Stars play their haptic before the favourite changes. On Overview, removing a chart rebuilds the favourites card, and Android drops a haptic from the detached star.
- Android 14+ uses the dedicated segment, toggle and drag effects. Older versions fall back to clock tick, long press and context click.
- **More → App preferences → Haptic feedback** (`haptics_enabled`, default on) turns every app haptic off. Android's Touch feedback setting still applies on top. Turning the switch on confirms with a toggle haptic; turning it off is silent.
- The full-backup preference validation treats `haptics_enabled` as a boolean.

**Fold stability (`FoldMonitor.java`)**
- A foldable's crease never divides the window, whether it is flat or half-open.
  - The monitor now notifies only when a physical hinge (a window extensions `TYPE_HINGE` that always hides content) appears, moves or goes away.
  - Book and tabletop postures therefore never rebuild, re-split or replay a page.
  - The Scooter, Rides and onboarding splits no longer move onto a crease.
- The hinge-angle sensor listener is removed. It only fed the posture label that used to trigger rebuilds.
- Real size changes, such as moving between the cover and inner displays, still lay out through the configuration change.

**Unfolded More split**
- `Panes` centres its gutter on the window. It measured its own position with `getLocationInWindow`, which includes translations, so a layout pass during the tab slide-in saw the page up to 48 dp further right. The menu then settled that much narrower until an unrelated later layout snapped it to its resting third.
- `Ui.layoutLeftInWindow()` now sums layout positions only.
- `Panes` corrects its split synchronously in `onLayout` before the first draw, instead of posting another layout.
- The hinge-aware splits in Rides, onboarding and the main two-pane view use the same helper.

## Validation

- `bash tests/run.sh`: all 22 suites pass.
- `bash build.sh` and `bash android-tests/build.sh`: the app and the separate runner build and verify.

Emulator fixtures ran on a read-only `Medium_Phone_API_36.1` AVD with Bluetooth off:

| Fixture | Cases | Result |
| --- | --- | --- |
| `run-responsive.py` | 26 layout cases, 320–852 dp; the five `all` cases include the fold check | All pass, three full runs |
| `run-rides.py` | 344 × 882 dark, 725 × 604 dark, 360 × 800 light at 1.4× | All pass, 123 checks each, four full runs |
| `run-live-stats.py` | 344 dark, 725 dark, 360 light 1.4×, 650 light 1.3×, 344 dark with animations off | All pass, two full runs after the `settle()` fix below |
| `run-battery-widgets.py` | Default cases plus saved slots | All pass |
| `run-charge-capture.py` | Default cases | All pass |
| `LaunchValidation` | Permissions denied; granted | Both pass |
| `DeveloperSettingsValidation` | Default; `preferences` with animations off | Both pass |
| `PhoneRideValidation` | `granted` | Pass |
| `DashboardValidation` | `layout-slots` | Pass |

After the switch-direction fix, the core suites, both builds, `run-rides.py`, `LaunchValidation`, both `DeveloperSettingsValidation` modes and `PhoneRideValidation` were rerun and pass. `LaunchValidation` (granted) crashed once during cleanup in that rerun, then passed three times in a row. The cause predates this branch:
- the fixture stops `ScooterService` and immediately clears preferences;
- on Android 11+, `Editor.clear()` calls listeners with a null key;
- the service's listener calls `key.equals(...)` on it if the service hasn't unregistered yet.

Real restores stop the service well before clearing, so only the fixture hits it.

**Fold check.** `ResponsiveDashboardValidation` feeds the activity a crease that goes flat, then half-open, then flat again, both horizontal and vertical. Throughout, Home keeps the same root view and neither hinge split is set. In all five cases the flat and half-open screenshots are pixel-identical. A physical hinge still moves Home's content clear of it.

**Haptics.** Checked on the emulator with `adb shell dumpsys vibrator_manager`, which lists every haptic the app plays with its constant:
- Tabs, segmented choices and a Top speed preset on the simulated scooter logged `SEGMENT_TICK` (26). Opening a Readings group and other ordinary taps logged nothing.
- Stars, preference switches, the Rides sensor rows and a simulated scooter setting (Cruise control) logged `TOGGLE_ON` (21) or `TOGGLE_OFF` (22) in the direction flipped. Tapping the switch or its row label made no difference.
- In dashboard edit mode, holding a widget's handle logged `DRAG_START` (25) once. Moving the widget into the next slot logged one `SEGMENT_TICK`. The favourites drag in `LiveStatsValidation` logged the same pair.
- With Haptic feedback off, nothing was logged: not the switch itself, segments or tabs. Turning it back on logged `TOGGLE_ON`, and ticks resumed.
- A plain framework `Switch` logs nothing, so `FeedbackSwitch` never doubles a system haptic.

Not covered on the emulator:
- The confirm haptic for the lock and for rides needs a real scooter; simulation refuses both.
- A simulated setting applies at once, so the case where a real scooter's switch waits for the reply was checked against `CompoundButton.performClick` instead. It flips the switch before calling the listener, so the state read beforehand is the one the rider asked for.
- How the haptics feel needs the owner's Pixel.

**More split.** Recorded at 5× animation scale in a 2362 × 2200 window (about 900 dp wide), measuring the menu's right edge on each frame:
- **0.12.0:** the edge settled at 1147 px, stayed there for 0.9 s of the slowed recording, then snapped to 1154 px.
- **This branch:** the edge arrives with the slide (1162 → 1154 px) and never moves after that.

The emulator ran its stale layout pass late in the slide, so its snap is small. On a faster phone that pass can come earlier, while more of the slide offset remains, which fits the reported quarter-to-third jump.

**Live Stats timeouts.** These are the occasional 180 s `run-live-stats.py` timeouts noted in `smooth-motion.md`; one hit the 725 × 604 case in this branch's first sweep. A thread dump of a stalled run showed the runner blocked in `Instrumentation.waitForIdleSync`, called from `settle()`, while the app's main thread was responsive, waiting in its message loop. The connected Live Stats pulse redraws every 40 ms, so on a loaded emulator a redraw is almost always pending and the idle wait can starve. `settle()` now waits for already-queued main-thread work and a 300 ms animation window, and `await(...)` still covers slower conditions. Two back-to-back stress runs of responsive, Rides and Live Stats then passed every case.

## Codex review follow-up

Codex reviewed `b31a500` and found two issues. Both are fixed.
- **[P2] A thumb swipe didn't save.** Android's `Switch` commits a thumb drag with `setChecked` and cancels the click, so click-driven switches only looked changed. Swiping Haptic feedback off left haptics on, and the next redraw flipped the switch back. The pattern predates this PR and also affected the other App preferences toggles and scooter settings. `FeedbackSwitch` now treats a committed drag as one click in the dragged direction, with one haptic.
- **[P3] Removing a chart on Overview was silent.** The favourite callback rebuilt the favourites card and detached the star before its haptic, and Android drops haptics from detached views. Stars now play first. The favourite toggles never refuse, so the state they play is exact.

`DeveloperSettingsValidation` now drives real touch sequences on a switch, not `performClick`:
- swiping Haptic feedback off, then on, saves each time and survives a redraw;
- a cancelled swipe changes nothing;
- a probe `FeedbackSwitch` that keeps showing its reported state, like a scooter setting, gets exactly one click per committed drag or tap, carrying the dragged state, and none for a cancelled drag or code changes.

Against the `b31a500` app the new check fails with "Swiping Haptic feedback off did not save". On the new head it passes by default (1,053 assertions) and in `preferences` mode with animations off (505).

On the emulator, real `adb input swipe` gestures and the vibrator log show:
- swiping "Simulate scooter as connected" starts the simulation and logs `TOGGLE_ON`;
- swiping simulated Cruise control on, then off, sends the setting each time ("Simulated locally; no scooter command sent") and logs `TOGGLE_ON`, then `TOGGLE_OFF`;
- removing a chart favourite on Overview logs `TOGGLE_OFF` and removes it.

The full sweep then passed on the new head: core 22/22, `run-responsive.py` 26/26, `run-rides.py` (123 checks × 3), `run-live-stats.py` 5/5, battery widgets, charge capture, `LaunchValidation` denied and granted, both `DeveloperSettingsValidation` modes and `PhoneRideValidation`.
