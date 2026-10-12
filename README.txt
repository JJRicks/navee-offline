NAVEE OFFLINE — VERSION 0.4.1

A local Android companion for an already activated and owner-bound NAVEE UT5 MAX.
Daily connection, locking, scooter settings, diagnostics and ride recording use
Bluetooth. The production app has no INTERNET permission or cloud login. It
leaves the phone's Wi-Fi and mobile-data settings alone.

This independent app is not affiliated with NAVEE. Its protocol reference is the
installed NAVEE Android app 2.1.9, checked against pepperonas/navee-st3-pro.
The named controls target the US UT5 MAX, model/PID 2538. Different models,
regions and firmware can expose different fields and behavior. The accompanying
verification report identifies controls and phone configurations actually tested.


0.4.1 fixes a launch crash on Samsung Android 16 by applying system bar styling
only after the app screen is attached. Install this APK over the previous version
to retain local setup and recordings.

GET CONNECTED

1. Power on the scooter and close NAVEE so it releases Bluetooth.
2. Open Navee Offline and allow Nearby devices. Android 8–11 calls its Bluetooth
   discovery permission Location; this app does not collect GPS coordinates or
   routes. Allow notifications to see the connection/recording Stop actions.
3. With a configured profile, launch connects to the last verified scooter by
   default. Without a saved address, use Connect and select the nearby scooter.
   Owner authentication, matching serial and fresh status establish readiness.
4. Use the prominent lock switch. It needs no confirmation dialog. The result
   comes from confirmed scooter state, not just a successful phone write.
   Pending actions, unavailable/stale status, reported motion and relevant
   scooter warnings can disable a change.

Startup never locks or unlocks automatically. A dropped connection or sleeping
scooter makes state unavailable; the app retries with backoff where a saved
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
  Battery, speed, estimated range, odometer, quick controls and connection/
  recording status. The large lock control stays easy to reach.

Controls
  Named OEM settings grouped by purpose. The catalogue includes lock, cruise,
  energy recovery, headlight/automatic lights, line/star light effects, turn
  sound, traction control, dashboard units, range-estimate mode, kick-start
  speed, configured maximum speed, charge ceiling/scheduling, Low Battery Mode,
  lock duration, Walk Assist, Power Assist and hill descent. Choices use the
  explained OEM domains and available firmware fields.

Stats
  All catalogued measurements, search, favourites, collapsible groups, source
  commands and reading ages. Measured signed power/voltage/speed charts preserve
  gaps. Protocol evidence includes read payloads and response status, with JSON
  diagnostics export.

Rides
  Persistent labelled recordings, notes, history, quality/coverage, charts,
  two-ride comparison and sensor CSV/analysis JSON export. Save separate Sport
  and Sport + Boost recordings to compare observations from your own rides.

More
  App, Scooters, Security, Lab and About sections, with one visible at a time.
  The section is remembered; switching sections keeps their existing views and
  scroll positions. Owner setup remains prominent until a profile is ready.
  App holds preferences; Scooters holds profiles/setup/backups; Security holds
  PIN/proximity tools; Lab holds experimental controls; About explains privacy.

The catalogue contains 36 named controls, including seven Lab candidates whose
UT5 MAX behavior is not assumed from another model's OEM code. Lab presents
evidence and an explained reversible domain. Missing status fields, unsupported
values and unmet prerequisites leave controls unavailable. A status field alone
does not prove a feature works. The verification report separates protocol
evidence, parked readback tests and untested physical behavior.

The app does not expose arbitrary opcodes, unbinding, factory reset, firmware
updates, cloud accounts/sharing, remote tracking or firmware downloads.


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

Owner identities, profiles, preferences and rides stay in private Android app
storage. Android backup/device transfer is disabled. Ordinary diagnostics/ride
exports exclude owner credentials and PIN responses. Diagnostics can include
device serials and other technical metadata. The separate private owner backup
intentionally includes the Bluetooth identity: treat it like a key. The
distributable APK/source contain no personal owner identity, account token or
saved scooter address. Do not add a private owner backup to a public archive.


READABILITY, CONNECTION OPTIONS AND FOLDING PHONES

More offers dark/light/system appearance, text scale, miles/km/both units and
comfortable/dense Stats rows. App units affect display; Dashboard units is a
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

On the Pixel 10 Pro Fold, native Android views for all five tabs were rendered
at outer-screen size. Expanded Home and rotated half-fold Home were checked
with controlled window state and OEM hinge callbacks. The phone was physically
folded/asleep during these checks; these are view renders, not physical hinge
transition tests or screen captures.

Two parked background recordings of 60 and 20 seconds saved with no journal
gaps, including logical outer/inner changes and the default background-connection
option off. Their service and wake-lock cleanup was checked. Longer recordings,
physical posture changes and a road ride remain untested. Ollie's phone was
unavailable, so its exact hardware/posture behavior remains unverified.
CSV/JSON export logic was tested; the Android document picker was not exercised
physically overnight. VERIFICATION.txt gives the exact scope.


BUILD AND TEST

Android 8.0 or newer with Bluetooth LE; source targets Android 36.

Run ./build.sh with installed SDK platform 36/build-tools 36.1.0 and JDK 9+;
JDK 17+ is recommended. Android Studio's Java runtime works.
ANDROID_SDK_ROOT/JAVA_HOME override paths. No Gradle or dependency downloads
are required. Default output: dist/navee-offline-lock.apk. Launcher name:
Navee Offline. The build verifies APK
signatures/alignment and rejects an unexpected INTERNET permission.

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

Run bash tests/run.sh for the five existing core Java suites; no Android SDK or
device is required. Build the app then bash android-tests/build.sh for the
separate instrumentation APK. README.md and CONTRIBUTING.md cover collaboration.

tests/ contains protocol, telemetry, named-command, identity-parser and ride-store
verification, plus helper tests where provided. Coverage includes malformed/
truncated replies, checksums, units/signs, bounded controls, validated identity
capture, same-report pairing, gap-aware integration, CSV escaping, restart
recovery and bounded sampling memory. The separate parked-validation
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
confirmation on the affected Samsung hardware remains pending.


LICENSE AND REFERENCES

MIT; see LICENSE and NOTICE. Protocol research reference:
https://github.com/pepperonas/navee-st3-pro
