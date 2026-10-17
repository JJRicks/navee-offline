# Connection and recording reference

Implementation details and measurement interpretation for Volt. Start with the [user guide](user-guide.md).

## Security and the handlebar fallback

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

## Understanding battery readings

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
temperature. Raw range readings come from the scooter's selected estimate mode.

New ride recordings use positive current as battery draw; older journals keep
their original direction interpretation. The obsolete direction picker is gone.
Unconfirmed old intervals remain absolute throughput, not net consumption.
Returned/input energy may include wall charging; it is not necessarily regen.

**Scooter → Range estimate → Scooter's estimate** enables drive-mode following,
which selects a supported firmware range estimator when fresh reports confirm
the scooter is stationary. Eco falls back to D-mode where firmware lacks an Eco
estimator. Unconfirmed attempts are bounded across reconnects.

The optional **Volt's estimate (learned)** uses that scooter's completed rides
for range in Scooter overview, Your scooter and Battery overview. Other reading
widgets, Live Stats, charts and exports retain the firmware prediction. Both
sources are estimates, not measured capacity or automatic Boost detection.
See [range learned from your rides](user-guide.md#range-learned-from-your-rides)
for the evidence threshold, fallback and limitations.

## Recording integrity

1. Connect, open Rides and start a recording with a useful label.
2. Add notes for mode, route, load or conditions.
3. Recording runs in the visible connected-device service; it does not depend
   on the screen layout remaining visible. It is designed to continue while
   folded, rotated, outside the app or with the screen off. The connection
   notification provides Stop. Active ride/charge work uses immediate foreground
   notification and a renewed bounded CPU wake lock. App preferences > Background
   recording explains Android notification and battery settings. Keep-screen-awake
   is optional. Real manufacturer battery policy remains a testing limit.
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

Basemap tiles are separate from GPS, the local route and recording. They start
off when no preference is saved; existing explicit choices remain. Use Basemap
below either map view or in App preferences > Map settings. The first new enable
explains provider access to your IP address and viewed area, with Enable basemap/Cancel
choices. The switch and acknowledgement are remembered across views and included
in a full app backup. Recording does not enable or request map tiles.

The Speed route overlay is drawn locally from saved fixes. Per ride it uses
scooter speed matched to each fix (same GPS epoch, reading at most 3 s old) when
that covers at least half of the drawn fixes or as many as GPS speed does;
otherwise GPS speed. It never mixes sources within a ride. Points without the
chosen source are no data (dashed gray), not zero. The scale is km/h internally:
0 to the 95th percentile (capped at the upper quartile plus three interquartile
ranges), rounded up to 5 km/h with a 10 km/h minimum; higher values use the top
color. Units change labels only. Segments are not joined across gaps.

## Ride exports and journal format

CSV/JSON exports use Android's file picker; you choose the destination. Volt does
not upload them. A cloud-backed document provider controls any subsequent sync.
See [Privacy](../PRIVACY.md#exports-are-different) before sharing an export.

The long-form scooter sensor CSV includes schema version, UTC date,
wall/monotonic timestamps, frame ID, sensor key, numeric/text value, unit, source
command, sensor time/age, current-direction interpretation and recording events.
`BEGIN` / `SENSOR` / `COMMIT` preserve sample boundaries; only committed frames
are accepted during replay. `GAP` / `HEARTBEAT` / `RECOVER` describe missing
exposure and process recovery without inventing samples. Analysis JSON contains
unit-explicit quantities, coverage and session metadata. `null` means
insufficient evidence, not zero.

Phone and motion journals are separate from the scooter journal. Combined JSON
can include precise route coordinates; GPX contains time and position. Inline
charts keep a lightweight selection of real samples, while fullscreen ride
charts load the complete committed source. Viewport drawing retains peaks and
gaps efficiently. Raw journals remain available for export.

## Background connections and foldable phones

More offers dark/light/system appearance, text scale, miles/km/both units and
comfortable/dense Stats rows. More > App preferences > Units > Time format follows the
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

Ride and charge recording are independent of this idle option. Their service
and ongoing Stop notification remain while hidden, with a renewed CPU wake lock.
Ride recording can reconnect, recording Bluetooth loss as gaps. An explicitly
started charge capture retries a temporary loss for up to five minutes, resuming
the same journal only after the same scooter authenticates and provides fresh
charging readings. Missing intervals stay marked as gaps and are excluded from
learned curves. Stop, explicit Disconnect, profile changes, disabled Bluetooth,
revoked permissions, fatal connection failures and recording limits end recovery.
A process restart does not silently resume a charge capture. Android 14+ may allow dismissing an ongoing notification
while unlocked. Force stop, real signal loss and vendor battery policy remain
limits; no service can collect scooter readings without a connection.

The interface adapts to compact/expanded windows. Folding a foldable halfway
(book or tabletop posture) leaves the screen exactly as it is: a crease never
reloads or re-splits a page. Only a physical hinge that hides part of the screen
keeps controls away from it. Bluetooth/recording belong to the service rather
than the layout.
