# Development and validation notes

Implementation invariants and native test guidance for contributors. Start with [CONTRIBUTING.md](../CONTRIBUTING.md) for the contribution workflow.

## Source map

| Location | Purpose |
| --- | --- |
| `src/local/navee/offlinelock/` | Native screens, Bluetooth authentication/controls, telemetry, profiles, recording and storage |
| `res/`, `AndroidManifest.xml` | Android resources, permissions, app version and component declarations |
| `build.sh` | SDK-only APK build, signing and verification |
| `tests/` | Core Java/Python tests and runner |
| `android-tests/` | Separate guarded device/emulator instrumentation and fixture runners |
| `setup/capture-owner.py`, `setup/owner-capture-guard.sh` | Computer owner-capture helper and on-phone capture cleanup guard |
| `setup/web/` | Static Chrome/USB setup wizard, pinned browser ADB bundle, recovery and security tests |
| `docs/`, `FEATURES.txt`, `VERIFICATION.txt` | User/technical references, per-control evidence and dated validation history |

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

## Model compatibility rules

`ModelRegistry` (versioned) resolves each scooter to one layer: `UT5_MAX`, `OEM_GROUP`, `SHARED`, `UNSUPPORTED_PRODUCT` or `UNSUPPORTED_TRANSPORT`. `CapabilityResolver` turns that into one decision per feature, and every UI gate and every write uses it.

- **PID provenance:** OEM group rules test only the advertised model ID (parsed from the raw advertisement by `AdvertisedIdentity`). The serial-derived PID (`Profile.pid()`) is Volt's adapter: it selects only the UT5 MAX layer (2538) or the shared layer. A serial-2538 profile always stays `UT5_MAX`, even if an advertisement disagrees (the conflict is diagnostic only), and keeps today's choices in every region; regional OEM rules for it are notes.
- **Advertised IDs** are written to a profile only after authentication and a serial match on that MAC (`BleController.remember()`); **Identify** captures one without a write.
- **One admission point:** every write goes through `MutationGate.check` when requested and again just before queueing; `BleController.send()` drops any frame that is not a read, 0x30/0x31 or the admitted mutation bytes. Unlock is the explicit recovery exception (fresh ready session, lock field present, no pending change). `AUTOMATIC_RANGE_FOLLOW` writes only on the UT5 MAX layer.
- **Model assumptions:** UT5 labels via `ModelRegistry.driveModeLabelProduct`; the UT5 charge reference, 716.04 Wh, photo and range following come from the same registry helpers. Measured telemetry stays universal.
- **Adding a mapping:** add a `FeatureVariant` (keys never change; a new domain is a new variant id), a registry row with its evidence, a `validPayload` change only with a paired negative test, and bump `ModelRegistry.VERSION`. Keep the US UT5 MAX golden tests green.
- **Registry rows** live in `ModelRegistry.oemRule`: the prefix tables are Volt constants (`Q`, `O`, `S` … plus per-rule literal arrays), and each row returns `oemMapped(variant, note, unlocked)`, `excluded()` (the official app hides it for that model), `null` (not mapped: the evidence is partial) or a mapped row refused for a model condition through `gated(…)`/`firmware(…)` (`REGION_RULE`, `FIRMWARE_OLD`/`FIRMWARE_UNKNOWN`, `FULL_PID_NEEDED`). Firmware gates use the meter version read in this session, compared like the official app (string order). Region rules are enforced only for riding controls and values.
- **Variants:** use `EXACT` readback unless the field is a bit (`MASKED`); a raw value with a known meaning outside the domain gets `withSentinel` (shown, never offered or sent). A value outside the domain is `VALUE_UNKNOWN`. `ModelRegistry.caution` holds confirmations the official app asks for; `chargeStartAllowed` holds the non-UT5 whole-hour start rule. The UT5 MAX never gets either.
- **UI:** another model's variant supplies values and labels (`MainActivity.featureLabel`); a control that is Lab on the UT5 MAX becomes an ordinary Scooter row when its variant is not Lab (`decision.variant.lab`), and its Lab entry says so.
- **Allowlist:** `ScooterCommands.validPayload` admits the union of the mapped domains (0x6e selector 1: 25/30/32/35/40/45/50/55/60/65/70 km/h; 0x53: 1 for group M). Each model still offers only its own table, and the UT5 MAX catalogue still refuses the extra values; paired negative tests cover the values just outside.
- **Speed family:** maximum speed is a fixed per-model table (`ModelRegistry.maxSpeedTable`), shown as a normal Scooter row instead of the UT5 MAX top speed card; Volt never sweeps or probes speeds.
- **New keys:** a control with no UT5 MAX counterpart (group S's `ambient_light_mode`, `logo_light`, `daytime_light`) lives in `ScooterCommands.oemOnly()`, never in `catalog()`, so UT5 MAX screens, saved layouts and the golden tests do not see it; the UT5 MAX layer resolves it as excluded, and a profile without identity evidence does not show it.
- `tests/OemControlsTest.java` holds the member/non-member tables, firmware and region edges, truncated 0x70 fixtures and frozen frame hex for each mapped family.
- `android-tests/CompatibilityGatingValidation.java` checks the UI gates with the developer simulator models (UT5 MAX, mapped 24431, unknown 39991) on a disposable emulator.

## Reports and local data

For a bug report, include app version, phone model, Android version, the relevant screen, expected behavior, and the minimal exception or steps needed to reproduce it. Remove owner identities, serials, addresses, account credentials, and unrelated phone data before attaching logs. Full Android bug reports and ride journals contain private data and are not source files.

Keep signing keys, passwords, owner setup/backup files, captures, local paths, and recordings outside Git. `.gitignore` covers common filenames, but review staged files before pushing. The optional `local-build.env` is a local shell configuration file, not a shared project requirement.

OEM decompiled files, phone reports, private recordings, owner setup files and
release signing keys are excluded from distributed source. Use synthetic fixtures
when contributing protocol, recording or recovery tests.

Historical verification is in `VERIFICATION.txt`. Release changes are listed in `CHANGELOG.md`; do not describe a feature as hardware-tested merely because it compiles or receives a Bluetooth acknowledgment.

## Phone sensor changes

Keep phone callbacks out of `RideStore.sample()`: a phone-only frame would break
paired electrical coverage. `PhoneRideRecorder` owns background callbacks and
batched writes; `PhoneRideStore` analyzes its sidecar journals. Preserve source
monotonic timestamps, uncertainty, unavailable readings, and segment boundaries.
Don't use accelerometer integration as route distance. Never add a map request to
the Bluetooth or recording path. Map tiles must remain independent of recording.
An absent `map_basemap_enabled` preference means false; preserve existing explicit
values. The Basemap switches below inline/full-screen maps and in Map settings
share this preference. A first new enable must explain provider access to the
IP address and viewed area, then wait for Enable basemap or Cancel before loading tiles.
Cancel leaves tiles off; acknowledgement persists only after approval. Full app
backup/restore includes the choice and acknowledgement. Changing provider alone
must not enable tiles, and refreshing a view must not count as consent.

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
count. New/reset dashboards prefer five columns and seed explicit supported-grid
rectangles from `DefaultDashboard`; never overwrite saved custom or empty dashboards.
Adaptive keeps its existing one- through four-column breakpoints; explicit
five/six-column choices are available only when the measured width permits.
Derive missing finer-grid positions from an available saved arrangement,
leaving its source untouched. Enforce each widget's minimum
width at the current measured width and combined app/system text scale. Temporary
window or font changes must not persist expanded rectangles until an actual edit
is committed. The grid, resize corners and accessible size dialog must use the same limits.
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
actions are icon-only, matching centered squares. At least 144 dp of available
width uses one row; narrower cards use two columns or one column. Short cards
and the narrowest Quick access cards use Move and an Options menu that retains
Edit, Duplicate and Remove without overlapping Resize. Targets are up to 52 dp
on one row and up to 48 dp when stacked; the resize corner remains 48 dp.
Keep accessibility names and glyph centers correct at every size. Options
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
text. Keep scooter readings stacked vertically beside the photo: newly added
cards start with odometer and drive mode, while the reset dashboard also includes
estimated range. Preserve scrolling from within longer custom content. Keep saved
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
labels for icon-only readings. Scooter Overview and Reading tiles omit visual age
footers even while connecting; keep cached values muted and their timestamps in
accessible descriptions. The disconnected header still shows Last seen, and
classic data widgets retain their existing age footers.

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

Quick access uses stable catalog IDs (QuickAccessActions) and a normalized 2–8
item list in each widget. Never accept arbitrary commands or navigation routes.
The dashboard invokes existing MainActivity editors and BleController gates;
experimental/security shortcuts must keep their explanatory approval flow.
QuickAccessGrid measures real label heights; do not clip them or shrink targets
below 48 dp to hide a scrollbar. The widget options no longer duplicate Size.
WidgetReveal is bounded and drawing-only: cancel on touch, render, focus loss,
reflow or close, and never move a card that is already visible.
The quick native mode exercises configuration, safe simulation actions, sheets,
responsive content and the new-widget cue.
