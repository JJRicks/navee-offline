# Contributing

Create a branch from `main`, make the change, and open a pull request with the behavior changed and the checks performed. Keep changes focused so they can be reviewed and tested on each phone.

```sh
git switch -c your-change
bash tests/run.sh
python3 setup/shizuku-dependencies.py --fetch  # explicit one-time pinned download
bash build.sh
```

Use JDK 17 or newer for development. The core Java tests require only that JDK; the APK build additionally needs SDK platform 36 and build-tools 36.1.0. A fresh clone builds with its own ignored local key. Source contributors do not need the private release signing key.

## Device checks

`android-tests/ParkedValidation.java` is a separate, explicitly invoked instrumentation runner. Its modes include real lock/settings writes and stationary recording sessions; choose a mode only when those actions are intended and the owner is present with a safely parked scooter. Navigation/background modes perform different checks and have documented guards. Read the chosen mode before running it.

`android-tests/DeveloperSettingsValidation.java` validates rounded preferences and
the hidden simulation menu on a disposable emulator with Bluetooth disabled.
Its default mode tests simulation isolation; `-e mode preferences` limits it to
the selectors and unlock gate. It refuses physical phones or existing profiles.
Compare the real telemetry database, profile/control-evidence preferences and
private ride files before/after the preview; require the real GATT, scanner,
command queue and recorder to stay idle. Include normal/reduced motion and a
compact large-text case when these controls change.

`android-tests/LaunchValidation.java` checks fresh launch, permissions, themes, and recreation. It refuses physical phones or existing app data and requires a fresh emulator. Run denied and granted-permission modes separately; granted mode requires Bluetooth off.

Preserve the app's confirmed-state lock behavior, permission/lifecycle guards, fixed ten-second idle background grace, and recording exception. Firmware settings use canonical raw units; mph/km display conversion must not change command values. Missing/stale samples must remain unavailable rather than becoming zeros or invented energy.

## Reports and local data

For a bug report, include app version, phone model, Android version, the relevant screen, expected behavior, and the minimal exception or steps needed to reproduce it. Remove owner identities, serials, addresses, account credentials, and unrelated phone data before attaching logs. Full Android bug reports and ride journals contain private data and are not source files.

Keep signing keys, passwords, owner setup/backup files, captures, local paths, and recordings outside Git. `.gitignore` covers common filenames, but review staged files before pushing. The optional `local-build.env` is a local shell configuration file, not a shared project requirement.

Historical verification is in `VERIFICATION.txt`. Current follow-up work is listed in `README.md`; do not describe a feature as hardware-tested merely because it compiles or receives a Bluetooth acknowledgment.

## Phone sensor changes

Keep phone callbacks out of `RideStore.sample()`: a phone-only frame would break
paired electrical coverage. `PhoneRideRecorder` owns background callbacks and
batched writes; `PhoneRideStore` analyzes its sidecar journals. Preserve source
monotonic timestamps, uncertainty, unavailable readings, and segment boundaries.
Don't use accelerometer integration as route distance. Never add a map request to
the Bluetooth or recording path. Map tiles are explicitly enabled in the viewer.

`PhoneRideValidation` and `UiOrganizationValidation` are fresh-emulator-only. They
refuse owner phones and existing app data; synthetic routes are test fixtures, not
physical sensor validation. Grant test permissions externally only on the emulator.
Use the existing release signing key privately for an in-place owner update.

## Dashboard and connected history

`DashboardValidation` exercises the actual widget palette, edit controls, cached
readings, and saved layout. Run it on a fresh emulator at narrow, compact, and
expanded widths. `HistoryValidation` uses isolated synthetic databases to check
profile separation, retention, query bounds, and storage pressure. Neither runner
belongs on an owner's phone; both reject physical devices.

Keep cached values separate from fresh authenticated state. History must not
enable scooter controls, extend the background grace, start phone sensors, or
change ride-energy integration. Preserve gaps between connections and do not
store owner identity or PIN traffic. Dashboard layout is per scooter and column
count. Dashboard defaults seed explicit one/two/three/four-column rectangles
only for new/reset dashboards; never overwrite saved custom or empty dashboards.
The two/four-column presets reproduce the captured folded/unfolded arrangement.
Adaptive keeps its existing one- through four-column breakpoints; explicit
five/six-column choices are available only when the measured width permits.
Materialize a new finer grid from the saved four-column arrangement (or another
available width), leaving its source untouched. Enforce each widget's minimum
width at the current measured width and combined app/system text scale. Temporary
window or font changes must not persist expanded rectangles until an actual edit
is committed. The grid, size chips and size dialog must use the same limits.
Widget scale values are stored in canonical units and converted for display.

Dashboard slots keep all one- through six-column layouts and widget options
together per scooter. Continue accepting existing slots with only one- through
four-column layouts and without the optional showDisconnect field (default true).
Editing or resetting the live dashboard must never mutate a saved slot. Validate
a snapshot before restoring it, and preserve intentionally empty dashboards.
`DashboardSlotsValidation` checks persistence, isolation and malformed snapshots
in an isolated emulator fixture. `DashboardValidation` with `-e mode layout-slots`
checks the real Save/Replace/Restore/default-reset dialogs, cancellation,
recreation, and large-text/light-theme actions on a fresh emulator. Both runners
refuse physical phones. Keep screenshot and width details in VERIFICATION.txt.

`ResponsiveDashboardValidation` checks the rendered default dashboard, control and
text bounds, and flat/half-open OEM fold callbacks. Its connected lock fixture only
changes views; it never puts Bluetooth into READY or sends a command. The companion
`android-tests/run-responsive.py` installs both APKs and clears app data on an
explicitly selected disposable emulator, then records screenshots and results at
multiple widths and text sizes. It refuses physical phones. Build both APKs with
the same key, start a disposable emulator, and pass `--adb`, `--serial`, `--app`,
`--runner`, and `--out`; use `--help` for individual cases. This is layout validation,
not a substitute for Samsung hardware or live Bluetooth testing.

Focused cases include `--case 344x882:1:editor` and
`--case 725x604:1:editor` for editor actions, plus `--case 360x800:1.4:gauge`
for the Gauge with larger app text. Gauge checks draw live, dual-unit, unknown,
stale and long negative readings at minimum/full widths and short/tall heights;
the number/unit block must remain inside the ring and range labels below its
endpoints with padding. Keep its visible and spoken Last seen state.

The editor uses dedicated top-left move grips and bottom-right resize corners,
with tap-accessible Move/Size dialogs. The grip, options gear, copy and red trash
actions are icon-only, matching centered squares on one row. Their targets are
48 dp where space permits, shrinking to 36 dp on the narrowest supported cards;
the resize corner remains 48 dp. Keep their accessibility names and glyph centers
correct at both sizes. Options
have one fixed ✓ Save footer and retain the top-right X; edits are not a new
transactional cancel/rollback flow. Preserve the one-lock-widget restriction and
32-widget limit when duplicating. The connected lock's Disconnect action defaults
on and may be hidden per widget; Connect/Cancel must remain available.

Undo/Redo are curved arrows in one rounded group immediately left of Done, with
the header action row below the scooter name on compact editing screens. History
holds up to 40 committed structural grid changes in memory: membership/order and
per-width geometry, retaining current widget settings. New real grid edits clear
Redo; no-op, cancelled gestures and settings-only edits must not. Done/Back,
leaving Home, screen recreation, default reset and slot restoration end the
current history. Never use layout Undo to affect scooter controls or recorded data.

Edit-mode transitions must change edit state synchronously, then animate only the
presentation. The 210 ms grid/header slide and control pop use at most one million
snapshot pixels for disappearing controls; recycle them on finish or interruption.
Consume grid taps spanning the transition so disappearing editor icons cannot hit
newly enabled scooter actions. Preserve disabled-control alpha. One shared clock
wiggles only visible cards by at most 0.2 degrees, without changing hit targets;
pause the gesture card and stop on focus/visibility loss, detach, close or disabled
motion. Preference selectors use a 190 ms slide and coalesced 210 ms theme/unit
rebuild so the animation can finish. Keep their labels readable and targets >=48 dp.

Developer simulation is an explicit runtime-only UI preview, not a BLE test mode.
Guard the service recording/history boundary AND the transport mutation boundary.
Close/cancel the real connection before sampling; never synthesize a GATT, owner
identity or raw BLE packets. Keep fake readings, settings and chart series in memory,
mark the UI and diagnostics as simulated, and clear them when simulation ends.
Do not persist them to profiles, control evidence, last-seen cache, telemetry/ride
history or capacity analysis. Background connection preferences must not keep the
simulation running. Preserve the real last-seen data and require an inactive ride
before entry. Native validation should compare storage and transport state, not
merely report a hard-coded mutation count.

The removal effect is decorative and must not delay deletion or neighbor reflow.
It uses a bounded snapshot (at most 2 megapixels and 2048 px per side) for a 200 ms
shrink/fade and soft puffs. Cancel it on another action or dashboard close, remove
the overlay on detach/completion and recycle the snapshot. Disabled animation or
snapshot failure must leave immediate removal functional.

Gesture previews must use an untouched start snapshot, pin the full requested
rectangle, compact neighbors without overlap, save only on release and restore
on cancellation or teardown. Preserve scrolling outside the handles. Animate
position and size from the currently rendered rectangle when a gesture interrupts
an earlier transition, then reset transforms on completion or cancellation.
Current timings are 140 ms for the active card, 210 ms for neighbors, 150 ms for
the destination outline and 180 ms for release. Respect system animation
settings. Lock motion is visual only: value and accessibility state must retain
the last confirmed report. Navigation arrival effects must not replay on telemetry
refreshes. Check direct drag, long press, corner resize, cancellation, theme
contrast and interrupted animations at each tested width. Executed results
belong in `VERIFICATION.txt`, not inferred from an implementation change.

Edit controls float over unchanged card content. Compare card, title, footer and
body-text geometry before/after entering Edit. The responsive matrix covers every
widget at minimum/default heights and narrow/default/full widths, including 140%
text. Keep the scooter's two default stats stacked vertically beside its larger
photo, and preserve scrolling from within longer custom content. Keep saved
positions/sizes and genuinely custom reading lists; the 0.6.3 migration changes
only the former default lists. Fit text from current available space rather than
a previously measured text height, so shrinking and growing a card is repeatable.
Balance short content within its viewport without forcing overflowing content
outside the reachable scroll area.

Your scooter's selected readings and local rename are presentation features.
Keep the owner-observed WALK/ECO/SPORT labels restricted to PID 2538 and the
confirmed mode codes (11/2/3). Unknown codes/models and raw diagnostics/exports
must retain their original values. Reported charge/throughput counters are not
measured capacity or current-ride consumption.

The battery cell should use the space remaining after its readings, preserving
readable percentage/range text and balanced visual placement. Check readings
in one row where they fit, then wrapping at narrow widths or large text. Preserve charging context switches, missing/stale semantics and spoken
labels for icon-only readings. Live footer removal must not hide stale timestamps.

Connection status remains concise; errors stay reachable by tapping it. Reuse the
profile's verified lastConnected timestamp rather than launch/attempt times.
DisplayTime applies time_format (system/12h/24h) to user-facing clocks. Keep
unknown timestamps empty and include dates for older connections. Dashboard
validation covers the taller centered, slightly inset slider, its full-width
narrow-card fallback, connection states and time formats using isolated synthetic
state; it must never issue a scooter command.

`SearchFocusValidation` uses a real visible software keyboard on a fresh emulator
to verify outside taps, scrolling, system Back, query preservation, and ordinary
form focus in both themes. Enable the emulator software keyboard; this runner
refuses physical phones and existing app data. Search bindings must pass touches
through, release listeners with the field, and leave ordinary form editors alone.
