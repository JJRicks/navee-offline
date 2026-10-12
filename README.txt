NAVEE OFFLINE — VERSION 0.6.3

A local Android companion for an already activated and owner-bound NAVEE UT5 MAX.
Daily connection, locking, scooter settings, diagnostics and ride recording use
Bluetooth, with no cloud login. Optional map tiles use internet; route capture
and local drawing do not. The app leaves Wi-Fi and mobile-data settings alone.

This independent app is not affiliated with NAVEE. Its protocol reference is the
installed NAVEE Android app 2.1.9, checked against pepperonas/navee-st3-pro.
The named controls target the US UT5 MAX, model/PID 2538. Different models,
regions and firmware can expose different fields and behavior. The accompanying
verification report identifies controls and phone configurations actually tested.


0.6.3 makes Home more glanceable: a larger battery percentage ring with icon
readings, just odometer/mode stacked beside a larger scooter photo, a taller
centered lock slider and concise connection status. Widget options use a gear, and the
power chart has a two-minute window. Clock format follows the phone unless
changed in App preferences. Saved card geometry and custom reading lists stay
intact; the old four-reading scooter default becomes the new two-reading list.
The floating edit controls, live drag previews, persistent last-known readings,
bounded history, grouped settings and explicit phone-sensor recording remain.
The confirmed 0.4.1 Samsung startup fix is retained. A same-signature update
retains setup and rides.

GET CONNECTED

1. Power on the scooter and close NAVEE so it releases Bluetooth.
2. Open Navee Offline and allow Nearby devices. Android 8–11 calls its Bluetooth
   discovery permission Location. GPS route recording asks for Location separately
   on newer Android versions. Allow notifications for connection/recording actions.
3. With a configured profile, launch connects to the last verified scooter by
   default. Without a saved address, use Connect and select the nearby scooter.
   Owner authentication, matching serial and fresh status establish readiness.
4. Use the prominent lock switch. It needs no confirmation dialog. The result
   comes from confirmed scooter state, not just a successful phone write.
   Pending actions, unavailable/stale status, reported motion and relevant
   scooter warnings can disable a change.

Startup never locks or unlocks automatically. A dropped connection or sleeping
scooter makes live state unavailable; any last-known lock state is grey and
unverified. It cannot enable an action. The app retries with backoff where a saved
connection exists. Reconnection does not replay a pending setting change.
Close NAVEE, check scooter power and use Connect if needed. Manual Disconnect
ends the local session.


FIRST SETUP FOR ANOTHER OWNER OR PHONE

Each person needs the Bluetooth identity for their own owner account/scooter.
It is a six-byte, 12-hex-character Bluetooth owner identity, not a NAVEE password
or cloud access token. ADB is required for the initial capture with the current
onboarding method; everyday use needs no computer or ADB.

More provides a tutorial, validated text/file import, separate local scooter
profiles and private owner-backup export. The earlier app's saved profile
migrates privately; it is not embedded in the APK.

The source includes setup/capture-owner.py. On a computer with Python 3 and
Android SDK Platform-Tools:

1. Install and sign into NAVEE on the phone. Keep your own scooter powered on
   and safely parked. Close other scooter apps.
2. Enable Developer options and USB debugging, connect a data-capable USB cable,
   unlock the phone and approve the computer's debugging prompt.
3. From the extracted source directory, run:

      python3 setup/capture-owner.py --output my-owner-setup.json --push

   With several phones attached, add -s PHONE_SERIAL from adb devices. If adb
   is not on PATH, add --adb /path/to/platform-tools/adb. Choose a new output
   filename if a backup exists; the helper does not overwrite it.
4. Select/connect to your scooter in NAVEE when the helper opens it. The helper
   captures fresh validated authentication rows, saves a private JSON setup,
   closes NAVEE and restores its temporary logging property. It does not save
   full phone logs or print the identity to the console.
5. Import navee-owner-setup.json from Downloads in the app's owner setup.
   Keep your desktop backup private. Remove the extra Downloads copy when it
   is no longer needed, and turn off USB debugging if you prefer.

The helper depends on the known OEM log format. A future NAVEE update may need
an updated capture method. It does not create a new cloud binding or replace
the official first activation/account-ownership process. An existing validated
authentication-only capture can be read with --capture-log FILE without ADB.


SECURITY AND THE HANDLEBAR FALLBACK

The electronic lock and configured four-digit handlebar PIN are separate ways
to retain access. More reads PIN state, changes/configures the owner's PIN and
enables a configured fallback PIN. PIN digits are excluded from ordinary
diagnostics, event logs and ride exports. Test handlebar unlocking while parked
before relying on it away from home. The in-app guide and official UT5 MAX FAQ
explain the button sequence:
https://service.naveetech.com/ut5-max/faqs/

The 6/12/24-hour Lock duration setting controls how long the scooter stays on
while electronically locked. Powering off after that duration does not prove
it will be unlocked. Keep the PIN fallback; do not rely on automatic unlocking
after 24 hours.

Proximity unlock requires this phone's Bluetooth bond and an enabled, configured
handlebar PIN. More shows the prerequisites and confirms the scooter setting.
Availability and physical behavior still depend on firmware.

Overnight validation read the existing PIN's configured/enabled state; it did
not change PIN digits, enable/disable the PIN, pair the phone or exercise
proximity unlocking. Local clock sync received command acceptance only: this
firmware exposes no clock readback. See FEATURES.txt and VERIFICATION.txt for
the evidence behind each feature.


FIVE ORGANIZED TABS

Home
  Editable widgets for connection/lock, battery, readings, charts, quick access,
  the recognized UT5 MAX photo (also visible offline), and recording. While editing Home, use the scooter
  widget's pencil to rename its local profile. The default layout puts lock first.

Scooter
  Named settings grouped into Ride, Battery, Lights, Dashboard & sound, and
  Security, with a scooter-name setting. The catalogue includes lock, cruise,
  energy recovery, tail/automatic lights, line/star light effects, turn
  sound, traction control, dashboard units, range-estimate mode, kick-start
  speed, configured maximum speed, charge ceiling/scheduling, Low Battery Mode,
  lock duration, Walk Assist, Power Assist and hill descent. Choices use the
  explained OEM domains and available firmware fields.

Stats
  All catalogued measurements, search, favourites, collapsible groups, source
  commands and reading ages. Last-known readings remain grey with measurement
  times after disconnection or restart. Measured signed power/voltage/speed
  charts preserve gaps. Protocol evidence includes read payloads and response
  status, with JSON diagnostics export.

Rides
  Persistent labelled recordings, notes, history, power coverage and comparison.
  Optional GPS, barometer and phone motion have separate raw journals. Saved
  rides show a local route, estimated grade and synchronized electrical readings.
  Export scooter CSV, route CSV, raw phone/motion CSV, combined JSON or GPX.

More
  App, Scooters, Security, Lab, Data and About sections, with one visible at a time.
  The section is remembered; switching sections keeps their existing views and
  scroll positions. Owner setup remains prominent until a profile is ready.
  App holds preferences; Scooters holds profiles/setup/backups; Security holds
  PIN/proximity tools; Lab holds experimental controls; Data & history holds
  connected-history preferences/storage/cleanup; About explains privacy.

The catalogue contains 36 named controls, including seven Lab candidates whose
UT5 MAX behavior is not assumed from another model's OEM code. Lab presents
evidence and an explained reversible domain. Missing status fields, unsupported
values and unmet prerequisites leave controls unavailable. A status field alone
does not prove a feature works. The verification report separates protocol
evidence, parked readback tests and untested physical behavior.

The app does not expose arbitrary opcodes, unbinding, factory reset, firmware
updates, cloud accounts/sharing, remote tracking or firmware downloads.


CUSTOMIZE HOME

Tap the pencil to reveal Add widget, Layout, movement grips and resize corners.
Drag the top-left dots to move a widget, or its visible lower-right corner to
resize it. A long press on the dots also starts movement. The full-size dashed
outline previews the destination while neighboring cards slide aside and fill
empty rows. Release to save that arrangement; cancellation restores the start.
Reflow takes 200 ms and the outline transition 120 ms; disabled system animations
are respected. Scooter/data actions inside widgets stay inactive; local scooter
renaming remains available. Tap Done when finished.

Use the gear for widget options; there is no duplicate bottom options bar.
Tap the dots for a Move dialog or the corner for Size if dragging is inconvenient.
Those dialogs are also available inside the options menu. Dialogs follow the
app's light/dark theme for readable controls and text. Widget menus, handles and
the scooter-name pencil appear only while global dashboard editing is on.
They float over the card on translucent backgrounds: widgets and their contents
keep exactly the same dimensions when edit mode is toggled. Editing does not
expand the cards or rearrange their content.

There are 15 widget types: Lock & connection, Your scooter, Battery overview,
Big reading, Gauge, Live trend, History chart, Reading list, Trip overview,
Battery health, Capacity experiment, Charging, Quick access, Ride recorder and
Note. Use up to 32 widgets for each scooter, with one lock widget. Options
include title/color, parameter,
selected readings, gauge limits, chart time window or note text as appropriate.
Battery, Your scooter and reading-list widgets can contain up to 12 chosen readings.

The seven defaults include lock/connection, Your scooter, battery, quick access,
recent power trend, Capacity experiment and recording. Existing custom layouts
are preserved; add Capacity experiment through Edit or restore defaults.
The battery card balances a large centered percentage ring with a thicker arc
above its readings. Route, lightning, heart and thermometer icons identify
estimated range, power,
controller-reported health and temperature along the bottom. These four readings
share a row where they fit; small cards and large text wrap them instead of
hiding values. Fresh charging status replaces that row with charging power,
current, estimated time remaining and temperature, keeping the percentage ring.
The extra Battery label beside the ring and repeated Live footers are removed.
Last-known readings remain grey and retain their timestamps.

Your scooter shows a larger UT5 MAX photo on the left for a recognized model,
including while disconnected, with configurable readings stacked vertically on the right.
Its defaults are odometer and drive mode. Reported charge count and battery throughput remain
in Stats and available as custom readings. Existing cards using the former four
reading defaults switch once to these two; other custom lists and saved card
positions/sizes are retained. New scooter cards use four rows and can shrink to
three. Use its pencil while editing Home to rename the local scooter profile.

As cards resize, narrow rows stack, values fit within readable font limits and
photos/rings/gauges/charts use the available space. Short content is balanced
within its card rather than gathering at the top. Saved card geometry remains
unchanged. Swipe anywhere inside long widget content to scroll it. Your scooter, Lock & connection, Gauge and
Ride recorder need at least three rows. Editing uses the same content geometry.

The taller lock slider is centered and slightly inset from the card edges.
Narrow cards retain the full available width for its touch target. Its connection
status is simply Connected, Connecting with an inline spinner, or Disconnected
with the last verified
connection time. Older connections include a date. Tap this line for connection
details or errors. Successful authenticated connection timestamps are already
saved per scooter; launching the app or a failed attempt does not invent one.

On UT5 MAX/PID 2538, owner-observed mode codes 11, 2 and 3 display as WALK, ECO
and SPORT. Other codes and models retain raw labels. This presentation does not
alter raw diagnostics or exported ride data. Charge count and lifetime throughput
remain reported counters, not a current-ride total or battery-capacity test.
Live SPORT is pale blue (darker blue on a light background), ECO is green and
WALK uses a green pedestrian icon. Stale modes remain grey; accessibility keeps
the spoken mode name and reading context.

Layout offers adaptive or one to four columns, limited to what fits the screen.
Each scooter retains separate arrangements for each column count. Moving or
resizing one card moves colliding cards out of its way. Restore default widgets
replaces only that scooter's dashboard configuration, keeping readings and rides.

Choose a parameter for Big reading, Gauge, Live trend or History chart. The
Live trend time-window selector includes two minutes, also for battery power.
The chart omits redundant signed and Recent readings/live labels; values and
recorded signs are unchanged. A chart
needs numeric samples actually received from the scooter; text-only or unsupported
fields cannot produce a graph. A gauge's scale is a display choice, not a new
scooter limit. Reported battery health is not a measured capacity test.


CAPACITY EXPERIMENT

Add the Capacity experiment widget to estimate a full-pack energy equivalent
from explicit recordings for the selected scooter:
  100 x summed recorded Wh / summed battery-percentage points used.
It refreshes about every ten seconds while viewed. A qualifying active ride
replaces its earlier contribution; it is never counted twice. The widget does
not start recording, Bluetooth, GPS or sensors.

Confirmed current direction throughout a ride permits using net energy drawn
minus returned. Unconfirmed rides form a separate absolute-throughput proxy,
which counts both draw and return and can overstate energy depletion. The two
evidence groups are never mixed. Each group needs at least 20 percentage points
of accepted battery drop before producing a result. A percentage of advertised
Wh is a comparison only, not battery health or measured capacity.

Each accepted ride needs at least one minute, 30 seconds of measured movement,
a five-point battery drop, 95% electrical coverage, and power/battery readings
aligned with both ends. Recording gaps/recovery, detected clock or direction
changes, missing endpoints and visible battery-gauge rebounds over one point
exclude rides. Sampled trends may miss brief rebounds. These experimental
filters do not establish a calibrated capacity measurement. Record ordinary
riding without charger input; no special discharge is requested.

The default evidence window is 90 days. Choose 7, 30, 90, 180, 365 or 730 days
in widget options; older rides are excluded without being deleted. Enter a
custom advertised energy in Wh, or enter zero for a known model's default.
The UT5 MAX reference is 716.04 Wh (46.8 V x 15.3 Ah), from NAVEE's US specs.
Other models require an entered reference to show a percentage comparison.

Cold, riding load, voltage range, gauge rounding and recalibration affect the
result. State of charge may represent charge rather than energy, so the same
pack can give different Wh equivalents in different charge windows. Available
pack temperatures and near-full/near-empty readings are shown as context; no
temperature correction is applied. This cannot prove an undersized battery.
README.md links the official rating and a battery-gauging engineering reference.


CHARGING READINGS

The battery overview automatically swaps its normal details for charging power,
current and a rough remaining time while the scooter freshly reports charging.
Its battery percentage and ring remain visible. Normal details return when
charging is no longer freshly reported. A separate Charging widget is optional.
Power uses the magnitude of paired voltage and current readings while the scooter freshly
reports charging. These are battery-side watts, not power drawn from the outlet
or a measurement of charger efficiency.

The estimate uses reported rated Ah, battery percentage still needed and the
current charging rate. A known charge limit below 100% becomes the target;
the card explains that charging to full requires raising that limit. An unknown
limit is identified explicitly; a displayed 100% estimate assumes charging
continues. Missing, stale or unpaired inputs leave affected readings unavailable.
Very low current allows power/current readings but no time estimate.
Tapering near full can take longer than this present-rate
estimate; it is not a guaranteed completion time or a capacity test.


LAST-KNOWN READINGS AND CONNECTED HISTORY

Each profile keeps its last-known readings and settings across disconnection
and app restart. Grey readings retain their measurement time. A cached lock
state is unverified; only a fresh authenticated connection and the existing
state/safety checks enable scooter changes. The cache never authorizes a write.

Save connected history defaults on. While connected, scooter observations can
build a local timeline without starting a ride. This does not start GPS, phone
sensors or a ride recording, and it does not extend the idle background timeout.
Last-known text fields are retained too; numeric fields support timeline charts.
Missing/disconnected intervals are represented as chart gaps.

History has a 50 MiB total storage budget across profiles. Detailed readings
are kept for up to seven days, minute summaries for up to 30 days, and daily
summaries for up to two years. Older records are trimmed sooner under storage
pressure. This budget is separate from explicit ride journals and map caching.

Open More > Data & history to inspect storage, turn off new timeline samples,
clear the current scooter's history or clear all connected history. Turning
history off retains the last-known cache for disconnected viewing. Clearing
removes the selected timelines and last-known cache; owner setup and explicit
ride recordings remain. Fresh readings can accumulate again while connected.


LIGHTS

Scooter > Lights contains Tail light, Automatic lights, line lighting and star
lighting. The former Headlight switch was misnamed: the OEM command controls
the tail-light setting. No separate manual main-headlight command is verified.
Use the scooter's power button for manual front-light control.

Automatic lights uses ambient brightness to control the headlight and tail
light. The OEM says a manual headlight change temporarily suspends automatic
control until the scooter restarts. The tail-light and automatic settings are
separate preferences; their readback does not measure visible light output.
An acknowledgement beep alone does not confirm which lamp illuminated.


PHONE SENSORS AND ROUTE MAPS

Start a ride from Rides while the app is visible. Precise Location gives the best
route; denied or approximate permissions do not prevent scooter recording.
GPS continues during Bluetooth gaps. Phone sensors run only during explicit
recordings and stop after the final buffered measurements are saved.

Phone sensors includes GPS route, motion on/off and Pocket/Mounted placement.
A barometer records when available. GPS/scooter acceleration is a speed derivative
in m/s²; raw accelerometer includes gravity, while linear acceleration removes it.
Earth-frame components require current orientation data. Pocket movement and
bumps can dominate phone readings; treat them as experimental.

Grade uses smoothed elevation across distance, not phone tilt. GPS altitude needs
strict accuracy and distance conditions, so some sections have no grade. Pressure
changes and GPS noise can still distort estimates. Route comparison includes GPS
distance, ascent/descent and the existing scooter energy measurements. It does not
plan or declare an optimal route before comparable rides have been collected.

Street map is optional in the route viewer and starts off. The default tiles are
OpenStreetMap, with visible attribution, normal caching and viewport-only requests.
No map account or API key is needed; another HTTPS raster provider can be selected
under Phone sensors > Map source. The provider sees the viewed area and IP address.
No scooter credentials or journal uploads occur. Routes still draw without tiles;
there is no bulk offline map-download feature. Map service/privacy details:
https://operations.osmfoundation.org/policies/tiles/
https://osmfoundation.org/wiki/Privacy_Policy

Older rides cannot acquire a route retroactively. GPS exports contain coordinates.
GPX contains time/position only; measured GNSS altitude/accuracy stays in CSV/JSON.

UNDERSTAND BATTERY READINGS

Fields include reported charge percentage, voltage, signed current, same-report
derived power, temperature, controller-reported health, reported charge count, lifecycle
counters, rated capacity and other fields where supplied. Ride/device data
includes speed, odometer, trips/history, firmware metadata, serial information
and warning/fault responses.

This UT5 MAX previously supplied 83 of 93 catalogued fields in a stationary
session. That is an observed response, not a guarantee that every field exists,
updates continuously or has a confirmed meaning on every unit. Readings change;
use live values and their ages. Unknown units/enum meanings remain explicitly
raw, including extreme-temperature charge exposure.

Reported health and health-adjusted capacity follow the controller's report/
calculation. A 100% health value does not measure actual usable energy or
guarantee advertised range. Lifetime throughput is not current-ride consumption.
Observed voltage change alone does not establish sag, cell balance or a fault.
This decoded protocol has no confirmed cell-voltage array or motor/controller
temperature. Range predictions come from the scooter's selected estimate mode.

Current direction starts Unconfirmed. Raw current sign alone does not establish
battery draw versus input. Rides initially integrates absolute measured
electrical throughput and labels it accordingly. Choose positive/negative draw
only after confirming charging and ridden behavior. Changes affect future
intervals without rewriting earlier evidence. Returned/input energy can include
wall charging; it is not guaranteed regenerative braking.


RECORD A RIDE

1. Connect, open Rides and start a recording with a useful label.
2. Add notes for mode, route, load or conditions.
3. Recording runs in the visible connected-device service; it does not depend
   on the screen layout remaining visible. It is designed to continue while
   folded, rotated, outside the app or with the screen off. The connection
   notification provides Stop. Keep-screen-awake is an optional on-screen
   riding preference. See the verification report for tested configurations.
4. Stop to save the session. New sessions preserve previous sessions.
5. Open details for coverage, moving/stationary throughput, voltage/temperature
   ranges, speed, power, charts and export. Choose two rides to compare.
   Deleting a saved recording asks for confirmation.

Available snapshots are recorded once per second privately. Electrical power
needs paired voltage/current from the same report, at most three seconds old.
Integration needs adjacent valid samples no more than three seconds apart.
Missing readings, disconnections and restarts do not become invented zeros or
energy. Gap exposure remains visible. Moving/stationary energy is separated
only where speed was measured.

Odometer distance is separate from speed-integrated distance. Energy per
distance requires at least 0.2 km of positive odometer change and 80% electrical
coverage. Results retain coverage; incomplete observations are not a battery
capacity test. Odometer reversal makes distance/efficiency unavailable.
Confirmed-direction and unconfirmed periods remain distinguished.

Data is appended in committed frames. On next app start an unfinished recording
can recover from process interruption or a partial final write; the interruption
is excluded from energy integration. Android Force stop prevents automatic
restart until you open the app again. A storage failure visibly needs attention:
free space and retry Stop to save the session. Recording cannot collect scooter
data while it sleeps or is outside Bluetooth range.


EXPORTS AND PRIVATE DATA

CSV/JSON exports use Android's file picker; you choose the destination. This
app does not upload them. If you choose a cloud-backed document provider, that
provider controls subsequent synchronization.

The long-form sensor CSV includes schema version, UTC date, wall/monotonic
timestamps, sensor key, numeric/text value, unit, source command, sensor time/
age and recording events. BEGIN/SENSOR/COMMIT preserve sample boundaries;
GAP/HEARTBEAT/RECOVER describe missing exposure. Analysis JSON has unit-explicit
quantities, coverage and session metadata. Null means insufficient evidence.
Charts retain a bounded selection of real points; the complete recorded sensor
journal remains available for export.

Owner identities, profiles, preferences, cached readings, connected history and
rides stay in private Android app storage. Android backup/device transfer is
disabled. Ordinary diagnostics/ride
exports exclude owner credentials and PIN responses. Diagnostics can include
device serials and other technical metadata. The separate private owner backup
intentionally includes the Bluetooth identity: treat it like a key. The
distributable APK/source contain no personal owner identity, account token or
saved scooter address. Do not add a private owner backup to a public archive.


READABILITY, CONNECTION OPTIONS AND FOLDING PHONES

More offers dark/light/system appearance, text scale, miles/km/both units and
comfortable/dense Stats rows. More > App preferences > Time format follows the
phone by default, or uses an explicit 12-hour/24-hour clock for displayed times.
Stats omits repeated explanatory UI text while retaining interpretation details. App units affect display; Dashboard units is a
separate scooter setting. Kick-start, maximum-speed and Lab cap selectors follow
the app units, including Show both. Fractional mph choices reflect the scooter's
fixed metric speed steps. Stored trips and minimum-distance hints follow the
same preference. Automatic launch connection, idle polling, background
connection and on-screen keep-awake are configurable. Keep idle connection in
background defaults off. With it off and no recording, hiding the app or turning
the screen off starts a 10-second grace period. If the app stays hidden and
no recording is active, the deadline ends the session, cancels reconnects and
removes its connection notification. Failed connections and retries cannot
extend that deadline. Returning to the app cancels it. Only leaving the
foreground starts this grace period: changing the option while already hidden
or stopping a hidden recording creates no new grace. With no recording and no
eligible grace period, the idle session ends. No electronic lock state changes
automatically. With automatic connection enabled, reopening the app can connect
again.

Ride recording is independent of this idle option. An active recording keeps
its service and recording notification while hidden, reconnects when needed,
and continues until you stop it. Recording uses a faster sensor cadence than
idle viewing; unavailable Bluetooth data remains a recorded gap.

The interface adapts to compact/expanded windows. It uses fold bounds when
supplied, keeps controls away from the hinge and supports a horizontal half-fold
arrangement. Bluetooth/recording belong to the service rather than the layout.

Earlier releases received parked scooter checks, short recorded-ride analysis,
and Samsung startup confirmation. Those observations do not validate every
current widget, physical fold transition, phone sensor or ridden scooter setting.
VERIFICATION.txt records executed checks and the remaining physical test scope.


BUILD AND TEST

Android 8.0 or newer with Bluetooth LE; source targets Android 36.

Run ./build.sh with installed SDK platform 36/build-tools 36.1.0 and JDK 9+;
JDK 17+ is recommended. Android Studio's Java runtime works.
ANDROID_SDK_ROOT/JAVA_HOME override paths. No Gradle or dependency downloads
are required. Default output: dist/navee-offline-lock.apk. Launcher name:
Navee Offline. The build verifies APK signatures and alignment. INTERNET supports
only the optional replaceable map-tile source.

Generated build files and local signing keys default to ignored .local/build
and .local/keys directories. NAVEE_BUILD_DIR, NAVEE_KEY_DIR and NAVEE_APK_PATH
override these locations; an optional ignored local-build.env can configure
local paths. The original development checkout uses this local configuration
to preserve its existing private release key outside the source repository.
Preserve that original key for updates retaining app data. A fresh clone
generates its own key unless NAVEE_SIGNING_KEYSTORE, NAVEE_SIGNING_ALIAS and
NAVEE_SIGNING_PASSWORD are supplied. Another signing key requires uninstall/
reinstall and restoring owner setup. Uninstall removes private recordings;
export anything you need first.

Run bash tests/run.sh for the core Java suites; no Android SDK or
device is required. Build the app then bash android-tests/build.sh for the
separate instrumentation APK. README.md and CONTRIBUTING.md cover collaboration.

tests/ contains protocol, telemetry, named-command, identity-parser, ride-store,
phone-route and dashboard-layout verification. Coverage includes malformed/
truncated replies, checksums, units/signs, bounded controls, validated identity
capture, same-report pairing, gap-aware integration, CSV escaping, restart
recovery, bounded sampling memory and collision-free dashboard placement.
The separate parked-validation
instrumentation APK is a testing tool, not part of the production app. Consult
the verification report for executed tests and physical scope.


BRIEF LEGACY HISTORY

0.1 established local UT5 MAX owner authentication and offline lock/unlock.
0.2 added battery/ride diagnostics and foreground CSV recording.
0.2.1 refined automatic connection and the large confirmation-free lock switch.
0.3 replaces the limited recorder with persistent background sessions and adds
the organized native interface, named OEM controls, onboarding/profiles,
security tools, configurable display and fold-aware layouts. Earlier notes
about foreground-only recording, replacing the previous CSV or automatic
24-hour unlocking do not describe this version.

0.4 adds section pickers, searchable scooter controls and compact headers outside
Home. Tap the selected main tab to return to its top. Idle background-off work
uses one fixed ten-second grace period; reconnect failures never extend it.

0.4.1 defers system-bar icon styling until the root view is attached, fixing the
early Samsung PhoneWindow launch failure. Fresh-emulator regression checks pass;
the friend subsequently confirmed startup on the affected Samsung.

0.5.0 adds explicit GPS/barometer/motion recording, route maps and grouped lighting.

0.6.0 adds the editable per-scooter dashboard, cached readings/settings and bounded
connected history. Lights is inside Scooter, keeping five main tabs. The former
Headlight label is corrected to Tail light based on the OEM command mapping.

0.6.1 refines dashboard editing with a pencil action, separate movement grip,
visible resize corner, full-size preview and animated compaction. Widget options
use one menu and themed dialogs. The battery ring gains a shorter % label, while
Your scooter gains direct renaming and configurable summary readings.

0.6.2 moves the scooter photo left and readings right, adapts card content to
available space and keeps widget edit controls inside global edit mode. Long
content remains scrollable and practical minimum heights preserve usable cards.
Editing uses floating controls over the same widget/content dimensions shown
during normal viewing. Search fields and surrounding filters/results gain
consistent padding and spacing.


0.6.3 trims repeated Live/status text, uses gear-shaped widget options, adds a
two-minute Live trend window and an app clock-format preference. The battery
ring takes priority over compact icon readings, and Your scooter defaults to
odometer/mode with a three-row minimum. Its old four-reading default is migrated
without moving/resizing cards or changing other custom reading lists. A taller
centered lock slider and concise connection status keep controls compact;
overflowing widget content accepts scrolling from within the content area. The battery arc
is thicker, scooter readings stack beside a larger photo, and content uses the
card's available space while preserving the saved layout.


LICENSE AND REFERENCES

Application source is MIT; see LICENSE and NOTICE. The user-supplied UT5 MAX
photo and map content have separate attribution and are not relicensed as MIT.
Protocol research reference:
https://github.com/pepperonas/navee-st3-pro
