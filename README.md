# Navee Offline

A native Android companion for an already activated, owner-bound **NAVEE UT5 MAX**. Bluetooth connection, lock/unlock, scooter settings, diagnostics, and ride recording work locally. An optional USGS Topo basemap uses internet access; controls and recordings require no server or account. Route geometry is drawn locally even when map tiles are unavailable.

Current application version: **0.12.0** (versionCode 24). Independent project; not affiliated with NAVEE.

Download the signed APK from [GitHub Releases](https://github.com/JJRicks/navee-offline/releases/latest). Install it over the earlier official APK to keep scooter setup, dashboards, and recordings. See [CHANGELOG.md](CHANGELOG.md) for release changes and [RELEASING.md](RELEASING.md) for the release process.

## What is here

Version 0.12.0 smooths navigation, dialogs and map loading, fixes brief scrollbar
flashes and scroll-position jumps, and adds grab-handle reordering for Stats
favourites. An **All widgets** example is added to saved-layout slot 3 only when
that slot does not already exist for the scooter. It contains all 15 widget
types with useful settings and arrangements for one through six columns.
Your active dashboard, other slots, profiles and recordings stay intact.
See [VERIFICATION.txt](VERIFICATION.txt) for executed checks and remaining
physical test limits.

- **Home:** an editable dashboard for each scooter, with automatic connection, a large lock switch, battery overview, charts, and shortcuts.
- **Scooter:** compact grouped settings for riding, battery, lights, dashboard/sounds, and security. Rename your scooter here or use its widget's pencil while editing Home.
- **Stats:** searchable grouped readings and charts for every recorded numerical parameter, shared chart windows, fullscreen inspection, and manually ordered favourites. Grey last-known values retain their measurement times.
- **Rides:** persistent scooter readings plus optional GPS, barometer, and experimental motion. Inspect original sample values in expandable multi-series graphs, rename and compare rides, view route overlays, and export raw CSV, joined CSV, JSON, or GPX.
- **More:** profiles, owner setup, security options, app preferences, experimental settings, and connected-history storage controls. Submenus are remembered for one minute away from the tab or app; a longer absence returns to the More index.

The named controls target the US UT5 MAX, model/PID 2538. Other models, regions, and firmware may differ. [FEATURES.txt](FEATURES.txt) and [VERIFICATION.txt](VERIFICATION.txt) distinguish implemented behavior from hardware checks.

## App preferences and UI preview

Open **More → App preferences → Units** for distance/speed, temperature and time format. These settings and Appearance use rounded selectors with a sliding highlight; some narrow controls scroll sideways. Celsius/Fahrenheit changes displayed readings and chart/gauge scales; stored telemetry stays in Celsius. Map settings and Background recording guidance also live in App preferences.

For UI testing, open **More → About & permissions** and tap the **Navee Offline version seven times**. **Developer options** then appears in More. Its **Simulate scooter as connected** switch supplies sample readings, local control responses and generated chart previews. A persistent sample-data banner includes **Exit**. Simulation closes the real Bluetooth connection, cannot start a ride or phone sensors, and never saves fake telemetry into your history, last-seen cache or battery analysis. Finish any active recording before enabling it. It is temporary and turns off when the service/app process restarts; normal profile and dashboard settings remain yours. Use **Hide developer options** to hide the menu again.

## Make Home your own

Tap the **pencil** to edit Home. Drag the top-left dots to move a widget, or drag its visible lower-right corner to resize it; a long press on the dots also starts a move. A dashed outline previews the full destination while neighboring cards slide aside and fill empty rows. Releasing saves the preview; cancelling restores the starting arrangement. Tap **Done** to use scooter controls again.

Each card has one row of matching square controls: the **move grip**, **gear** for options, **copy** for Duplicate, and a red **trash icon** at the right for Remove. The icons stay centered. Below 96 dp, a narrow card keeps separate Move and Options targets; Options opens Edit, Duplicate and Remove actions. The options sheet keeps its **X** at the top-right and has just **✓ Save** in its fixed footer. The move, options and duplicate controls stay grouped on the left, with trash at the right. For a drag-free alternative, tap the dots for **Move** or the corner for **Size**; the redundant Size row is removed from widget options. Widget menus, handles and the scooter-name pencil appear only while global dashboard editing is on. Dialogs follow the app's light/dark theme.

Curved **Undo/Redo** arrows share a rounded control immediately left of **Done**. On compact screens, this action row sits below the scooter name. Undo keeps up to 40 moves, resizes, additions, deletions and duplicates during the current editing session, retaining the widgets' latest settings. A new layout change clears Redo; cancelled gestures, unchanged actions and settings-only edits do not. Finishing editing, leaving Home or recreating the screen ends this temporary history. Restoring defaults or a saved layout starts fresh; use the saved-layout slots to keep arrangements beyond an editing session.

**Layout → Columns** offers **Adaptive** or one to six columns in a rounded selector with a sliding red highlight. Swipe the selector sideways if it does not fit. Adaptive retains the familiar one- through four-column choices; five and six columns are optional on screens wide enough to support them. Layouts remain separate for each column count. New five/six-column arrangements start from the saved four-column layout where available, without resetting it. In six columns, a four-column scooter card beside a two-column lock card gives a two-thirds/one-third split. Each widget has a minimum usable width that also accounts for text size, so six columns give finer sizing steps rather than forcing six tiny cards onto every row.

Editing keeps each widget and its content at the same dimensions as normal viewing. Translucent controls float over the card, so entering or leaving edit mode does not expand cards or rearrange their contents.

Entering and leaving edit mode briefly slides the dashboard and pops its controls into place. Visible widgets have a very subtle wiggle while editing; the card being dragged or resized stays steady. The wiggle keeps its 0.2° amplitude with a quicker 620 ms cycle, and stops when the app loses focus. Saving a newly added off-screen widget gently scrolls it into view and briefly outlines it in white; touching the page stops the cue. With reduced motion, positioning is immediate and the outline is steady.

Cards animate their size and position together during layout changes. Removal has a brief shrink-and-fade with soft puffs while the other cards can move immediately. Short transitions also follow deliberate page changes, button presses, and confirmed lock-state updates. They respect Android's disabled-animation setting; movement never substitutes for the scooter's confirmed lock state.

Navigation uses consistent short fades and glides; dialogs rise smoothly and
map content/tiles appear without abrupt layout jumps. Restored pages start at
their saved scroll position, and scrollbars appear when you scroll rather than
flashing on each settings rebuild. Transitions become immediate when Android
animations are disabled.

**Edit → Layout → Saved layouts** offers three named slots for each scooter.
Save captures every widget and its options, the column choice, and all saved
one- through six-column arrangements together, including folded and unfolded
layouts. You can restore defaults or experiment with the current dashboard
without changing the slots, then restore a slot to return to that setup. Saving
over an occupied slot and restoring a slot ask for confirmation. Save your
current dashboard in another slot before replacing it if you want to keep both.
Column choice is shared across all scooters on this phone; restoring a slot
also restores that shared choice without replacing other scooters’ widgets.
Slots are local to this phone and scooter; they contain layout settings, not
readings, recordings, or owner credentials. Even an empty dashboard can be saved.

Slot 3 receives an **All widgets** example only if no saved slot 3 exists for
that scooter. Adding the example never overwrites a saved slot, including an
intentionally empty layout. The example includes all 15 widget types with sensible parameters
and one- through six-column arrangements. Open **Edit → Layout → Saved layouts**
and restore **All widgets** to try it; save your current dashboard in another
slot first if you want to return to it. Adding the example does not activate it
or change your current column choice.

The 15 choices are Lock & connection, Your scooter, Battery overview, Big reading, Gauge, Live trend, History chart, Reading list, Trip overview, Battery health, Capacity experiment, Charging, Quick access, Ride recorder, and Note. Customize titles, parameters, reading lists, colors, chart windows, and gauge ranges where applicable. Up to 32 widgets are allowed, with one lock widget. New widgets remain drafts until **Save**; closing without saving leaves the dashboard unchanged. A recognized UT5 MAX photo remains visible while disconnected. Existing custom layouts are preserved; add widgets through Edit or restore defaults.

**Quick access:** open its gear → **Quick settings** to choose two to eight ordered shortcuts. Tap an item to replace it, use the up arrow to move it earlier, or add/remove items. Ordinary scooter toggles work directly; controls such as kick-start speed and energy recovery open their normal editors above Home. Scooter groups and app-preference menus open temporary panels; Stats and Rides remain page shortcuts. Existing live-state, parked, security and experimental-control checks still apply. Buttons wrap their labels and adapt their padding to fit four items cleanly at moderate card heights; short cards remain scrollable. Saved slots and duplicates retain the chosen actions.

New dashboards and **Layout → Restore default widgets** use the owner's ten-widget setup and saved widget options, including maximum-speed and scheduled-charging Quick access shortcuts. The preset selects five columns where they fit and adapts to fewer columns on narrower screens. Existing current layouts, custom options and all three saved slots are preserved by the update; restoring defaults is an explicit action.

**Your scooter** places a larger photo on the left and stacks configurable readings vertically on the right, defaulting to just odometer and drive mode. These two values grow into the available space, with a moderate size cap and automatic shrinking in compact cards. Reported charges and battery throughput remain in Stats and can still be selected for a custom widget. The former four-reading default is updated once to these two readings; other custom lists and saved card positions/sizes are preserved. New scooter cards use four rows and can shrink to three. Use the pencil beside its name while editing Home to rename the local profile. On the UT5 MAX (PID 2538), owner-observed mode codes 11, 2, and 3 display as WALK, ECO, and SPORT; other codes and models retain raw labels. Raw diagnostics and ride exports remain unchanged. The battery cell uses a compact **%** label.

Live SPORT is pale blue in the dark theme and a readable darker blue in the light theme; ECO is green, and WALK uses a green pedestrian icon. Last-known modes stay grey, with their mode names retained for accessibility.

Card contents adapt as you resize: rows stack at narrow widths, values fit within readable font limits, and photos, battery cells, gauges and charts use the available space. Short content stays balanced within its card instead of gathering at the top; saved card positions and sizes stay intact. Swipe anywhere within overflowing widget content to scroll it. Your scooter, Lock & connection and Gauge keep practical minimum heights. A wide Ride recorder can use one row; narrow Quick access rails can use three rows while retaining separate editing targets.

**Gauge** uses a 270° ring around a larger centered value and unit, with space below for the minimum and maximum labels. Parameter formatting and grey stale readings retain their existing behavior.

The battery overview uses a tall red battery cell with percentage and estimated range, with power, reported health and temperature readings underneath. Narrow cards and large text wrap readings rather than hide values. While charging, a separate card above the widgets shows the scooter, progress to the charge limit, estimated time and battery-side power. Last-known readings stay grey and retain their timestamps.

Lock & connection is a full-width bar: tap anywhere on it or slide it to lock or unlock. When locked, the white knob sits on the right; when unlocked, the red knob sits on the left. A **Disconnect** button is shown while connected by default; turn off **Show Disconnect button** in this widget's options to hide it. This option never hides **Connect** or **Cancel** while disconnected or connecting, when the card shows the last verified lock time. The connection line in each page header shows Connected, Connecting or Disconnected with the last-seen time; tap it for details and to connect or disconnect. **More → App preferences → Units → Time format** follows the phone by default, or selects 12-hour/24-hour clocks for displayed times.

The Live trend widget supports a **2 minutes** window, including for battery power. Chart widgets also offer **Relative/Absolute** Y-axis scaling; percentage axes stay within 0–100, and signed power/current can center on zero. Redundant “signed” and “Recent readings • live” labels are removed from the chart presentation; the underlying values and recorded sign remain unchanged. Stats also omits repeated instructions while retaining technical explanations that affect interpretation.

Scooter, Lights, Stats, and the widget parameter picker’s search fields release focus when the keyboard is dismissed or you tap outside, keeping the current filter. Consistent inner padding and space between search, filters and results make these controls easier to scan.

Charts need numeric readings actually supplied by the scooter. Unsupported or text-only selections have no numeric graph. Reported battery health is the controller's value, **not a measured capacity test**.

## Capacity experiment

The **Capacity experiment** widget compares recorded electrical energy with the battery gauge's percentage drop: `100 × summed Wh ÷ summed percentage points used`. It analyzes the selected scooter's explicit ride recordings, including the current recording when it qualifies, and refreshes about every ten seconds while viewed. The active ride replaces its previous contribution. It starts no recording, connection, GPS, or sensors of its own.

With current direction confirmed throughout each accepted ride, the widget uses net energy drawn minus returned. Rides with unconfirmed direction form a **separate absolute-throughput proxy**; that counts both draw and return and may overstate energy depletion. The two groups never mix, and each needs at least 20 battery percentage points before showing a result. The percentage beside either result compares it with the advertised energy; **it is not battery health or a measured capacity percentage**.

Each ride must last at least one minute, include 30 seconds of measured movement, use at least five battery percentage points, and have at least 95% electrical coverage. Battery and power readings must align with both ends. Gaps, recovery, detected clock/direction changes, missing end readings, and visible gauge rebounds above one point exclude a ride. Sampled trends can miss brief rebounds. These are experimental screening rules, not a validated capacity test. Record ordinary riding without charger input.

The window defaults to **90 days**, with 7, 30, 90, 180, 365, and 730 days available. Older rides fall out of the calculation without being deleted. Set a custom advertised energy in Wh in the widget's options; zero uses the known model rating. The UT5 MAX reference is **716.04 Wh (46.8 V × 15.3 Ah)** from [NAVEE's US specifications](https://naveetech.us/products/ut5-max). Other models need an entered reference for a percentage comparison.

Cold, load, voltage range, gauge rounding and recalibration can shift this estimate. State of charge can represent charge rather than energy, so different sections of the same healthy pack can produce different Wh equivalents. The widget reports available pack temperatures and flags near-full/near-empty readings without applying a correction. [Texas Instruments' battery-gauging comparison](https://www.ti.com/lit/an/sluaar3/sluaar3.pdf) explains why proper capacity learning also accounts for gauge behavior and operating conditions; it is an engineering reference, not evidence about which gauge NAVEE uses. This experiment cannot prove that the pack is undersized.

## Charging readings

The battery overview automatically switches to separate **Charging power** and **Time to full** tiles while the scooter freshly reports charging. Normal readings return when charging is no longer freshly reported. The separate **Charging** widget remains available. Power uses paired battery voltage/current and measures the **battery side**, not outlet consumption or charger efficiency. Live power is red for draw and green for charging; stale readings are muted.

The fallback time estimate uses reported rated Ah, the remaining percentage and the current charging rate. A known charge limit becomes the target; missing, stale or unpaired inputs leave affected readings unavailable. Tapering near full can make the current-rate estimate optimistic.

**More → Developer options → Charge recording** offers explicit recording with a manually selected **Stock** or **High-speed** charger label. The app does not detect the charger. Qualified sessions learn separate curves for each scooter/label; a learned time is used only when live power matches the saved sessions at the current battery level. Otherwise it falls back to the current-rate estimate. Neither method is a promised completion time or capacity test.

Charge recording saves bounded local samples every ten seconds and stops on disconnect, completion, an explicit Stop, or an ending/error condition. **Export charge recordings** saves the selected scooter's versioned JSON through Android's file picker, including original electrical readings, timestamps, gaps and the manual charger label. Export requires capture to be stopped and excludes owner credentials, names, MAC addresses, PINs and locations.

## Last-known readings and connected history

Last-known stats and settings stay on the phone for the selected scooter. Grey values are historical; a grey lock state is unverified until you reconnect. Only fresh authenticated scooter state enables setting changes.

Connected history starts enabled and saves scooter readings while connected, without starting a ride, GPS, or phone sensors. Its total storage budget is **50 MiB** across profiles: detailed samples for up to seven days, minute summaries for up to 30 days, and daily summaries for up to two years. Older samples can be trimmed sooner to stay within the budget. Use **More → Data & history** to inspect storage, stop new timeline samples, or clear one scooter's history or all history. Turning history off retains the last-known cache; clearing removes both its selected timelines and cache. Ride recordings and owner setup are separate.

The usual ten-second idle background timeout is unchanged. History does not prolong a connection or turn an ordinary connection into a ride recording.

## Live Stats and range estimates

**Readings** keeps its dynamic reading count, search and categories; the redundant favourites-only filter is removed. **Charts** uses the same searchable categories and offers every numerical parameter retained for the selected scooter or currently reported. One **Window** picker applies to all charts: 2 minutes, 10 minutes, 1 hour, 6 hours, 24 hours, 7 days, 30 days or All. All eight choices appear together, including on narrow screens. Stars add charts to **Overview**, where readings and charts share a reorderable favourites list. Tap the Reorder icon, then drag a row by its handle; dropping saves the order, while a cancelled drag restores the starting arrangement. Screen-reader Move up/Move down actions remain available. Each graph expands for inspection. Only visible charts load data; missing intervals remain gaps, and older retained history may be summarized.

**Follow riding mode** selects the supported firmware range estimator for the current mode when fresh state confirms the scooter is stationary. Fixed choices disable following for that scooter. Missing Eco support falls back to the firmware's D-mode estimator, with an explanation in the picker. Range displays still use the scooter's prediction; they do not measure usable capacity or automatically detect Boost. Unconfirmed automatic attempts are bounded across reconnects.

## Lights

All lighting controls are under **Scooter → Lights**. The former “Headlight” switch is correctly named **Tail light**: OEM code identifies that command as the tail-light setting. **Automatic lights** uses ambient brightness to control the headlight and tail light. The app confirms configuration readback, not visible light output. No separate main-headlight command is verified; use the scooter's power button for manual front-light control. The OEM describes manual headlight changes as temporarily suspending automatic control until the scooter restarts.

## Build

This is a Java Android project with an SDK-only shell build, rather than a Gradle project. Shizuku dependencies are fetched explicitly once and verified against pinned SHA-256 hashes; normal builds remain offline. Use macOS, Linux, or a suitable shell environment with:

- Android SDK platform **36**, build-tools **36.1.0**, and platform-tools for installation.
- A JDK supporting `javac --release` (JDK 17 or newer).
- Python 3 for the explicit dependency fetch/verification and generated safety-guard source.

```sh
git clone https://github.com/JJRicks/navee-offline.git
cd navee-offline
export ANDROID_SDK_ROOT=/path/to/Android/sdk
export JAVA_HOME=/path/to/jdk
python3 setup/shizuku-dependencies.py --fetch
bash build.sh
```

The APK is written to `dist/navee-offline-lock.apk`. The build checks its signature and alignment. Temporary build files and a new local signing key live in ignored `.local/` directories.

**Signing matters:** a fresh clone generates its own signing key. It cannot update an installed APK signed with a different key. Preserve the original key privately for release updates that retain local app data. Contributors can submit source changes without possessing that release key. Uninstalling the app removes private profiles and recordings; export them before replacing an installation.

Build paths can be overridden with `NAVEE_BUILD_DIR`, `NAVEE_KEY_DIR`, and `NAVEE_APK_PATH`. The optional ignored `local-build.env` accepts local shell assignments. Custom signing uses `NAVEE_SIGNING_KEYSTORE`, `NAVEE_SIGNING_ALIAS`, and `NAVEE_SIGNING_PASSWORD`. Do not commit local configuration or signing material.

## Test

Run the existing core suites without an Android device or Android SDK:

```sh
bash tests/run.sh
python3 tests/owner-capture-guard-test.py
```

They cover framing/authentication, telemetry decoding, named commands, owner identity parsing, ride persistence/energy integration, phone route quality/continuity, and dashboard placement. GitHub Actions runs these suites on pushes and pull requests.

For the separate Android instrumentation runner, build the main app first, then run:

```sh
bash android-tests/build.sh
```

Read [CONTRIBUTING.md](CONTRIBUTING.md) before using instrumentation: some parked-validation modes deliberately change scooter settings. The launch runner is restricted to an empty emulator installation.

## First setup and everyday use

Each owner needs their own Bluetooth owner identity from their already-bound NAVEE account. First capture can use the guided [Chrome/USB helper](https://pairscooter.jjricks.com/), the optional **Use Shizuku on this phone** route on Android 11+, or the existing computer ADB/Python helper. Daily use needs none of these helpers. No owner credentials are embedded in this repository or APK. The [browser helper documentation](setup/web/README.md) explains its explicit consent stages, local processing, recovery, and self-hosting.

The Shizuku route guides installation/start, explicit permission for broad debugging access, capture consent, and a separate import confirmation. It accepts shell-backed Shizuku 13+ only, reads fresh authentication frames from NAVEE's verified Android UID, restores the previous logging setting and stops NAVEE before making the identity available. The identity remains in memory until an explicit import; it is not uploaded or copied to Downloads. A detached safety guard and non-secret cleanup journal handle interruption. See [setup/SHIZUKU.md](setup/SHIZUKU.md) for its permissions, lifecycle and limitations. Never run the legacy computer capture helper simultaneously with another capture method.

The Chrome/USB helper remains a testing preview in this app release. A successful real-scooter capture through either new helper is still awaiting validation; the available phone checks were performed with the scooter out of range. A developer can also run the static helper locally using the linked instructions, or use the existing desktop helper. Browser capture currently supports the main personal Android user only, not work profiles or secondary users.

On first launch the app opens a step-by-step setup: choose the desktop helper, Shizuku, the Chrome/USB preview, import a backup file or enter a 12-character identity, then follow the selected route through capture, import, Nearby devices and connecting. On wide and unfolded screens, a step list appears beside the current numbered step. The tab bar appears once setup is finished. **More → My scooters → Run setup again** reopens it. These helpers use NAVEE already signed in on the phone; they add no NAVEE or Google account sign-in to this app or the setup website.

See [SETUP.txt](SETUP.txt) for the capture helper and private backup/import instructions, and [README.txt](README.txt) for detailed app behavior and recording interpretation.

## Phone recordings and maps

Start from **Rides → Start recording** while the app is visible. Choose precise location for a useful route; denying location still permits scooter and motion recording. GPS and phone sensors stop when the recording stops. Active ride/charge capture uses an immediate ongoing notification with Stop, foreground-service protection and a renewed bounded CPU wake lock. **More → App preferences → Background recording** explains notification/battery settings. The normal ten-second idle timeout still applies outside recordings. Android may let an ongoing notification be dismissed while unlocked; force stop, lost Bluetooth, and manufacturer battery policies remain limits.

The phone journal is separate from the scooter energy accumulator. GPS continues through a Bluetooth gap. Missing or poor fixes split the route; grade uses a distance window, preferably barometric elevation, with stricter limits for GPS altitude. Implausible altitude excursions are excluded from derived elevation, grade and ascent while raw measurements are retained. GPS and scooter speed derivatives are separate from the phone's raw and gravity-free acceleration. Pocket movement, vibration, pressure changes, and GPS error remain limitations.

Saved rides bring route, phone sensors and scooter readings together. The expandable graph supports power, wheel/GPS speed, voltage, battery, relative elevation, GPS acceleration and instantaneous energy per distance. Tap or slide to inspect original values and actual sample times, pinch to zoom, and pan with two fingers or the time labels. Each series has its own scale. Inline graphs use a lightweight preview; fullscreen loads the full committed recording asynchronously, including short peaks skipped by the preview. Loading failures retain the preview and offer Retry. Rename with the pencil beside the ride title. New rides use positive current as battery draw; older recordings retain their original interpretation.

Route maps offer power, grade, GPS acceleration and elevation overlays. Pinch, pan, tap to inspect, or use zoom/Fit. **More → App preferences → Map settings** controls basemap use and the USGS, OpenStreetMap or custom HTTPS provider. USGS Topo is enabled by default for new preferences; existing explicit choices are retained. It provides U.S. roads/terrain without an account or key, with attribution and cached viewport visits. Close zooms enlarge level-16 tiles rather than requesting unsupported detail. [USGS terms](https://www.usgs.gov/faqs/what-are-terms-uselicensing-map-services-and-data-national-map) and the [OpenStreetMap tile policy](https://operations.osmfoundation.org/policies/tiles/) apply to their respective providers. Denied or `X-Blocked` responses pause requests and never replace the local route with an error picture.

Map requests disclose the viewed area and IP address to the selected provider; credentials and ride journals are not uploaded. No city/route prefetch or bulk download is offered.

Old recordings have no phone route to reconstruct. Exported route/phone files contain coordinates; raw motion includes device axes, gravity-free acceleration, earth-frame acceleration when orientation is available, and rotation quaternion. GPX exports positions/time only because Android GNSS altitude is referenced to an ellipsoid; altitude and its accuracy remain in CSV/JSON. Raw journals remain available if a large file cannot be previewed.

## Known follow-up work

- The friend confirmed that **0.4.1 opens on Samsung**. That does not establish the physical behavior of every newer feature. The dashboard and automatic reconnection have been checked through 0.6.6 on the owner’s Pixel. Outdoor GPS/barometer/IMU accuracy and the new dashboard on the friend’s Samsung still need the checks listed in [VERIFICATION.txt](VERIFICATION.txt).
- Investigate the telemetry field `energy_throughput_wh`, displayed as **Energy counter (unverified)**. Observed values can decrease on this firmware, so its lifetime-counter meaning is unverified. Do not use its delta for battery-capacity conclusions. RideStore's sampled voltage/current integration is separate.
- Three short real ride recordings have been checked for electrical integration consistency. A controlled full usable-capacity or range test remains outstanding. Reported health and rated Ah are not capacity measurements.

## Source map

| Location | Purpose |
|---|---|
| `src/local/navee/offlinelock/` | Native screens, Bluetooth/authentication, telemetry, profiles, ride storage |
| `res/`, `AndroidManifest.xml` | Android resources, permissions, app version |
| `build.sh` | Local APK build/sign/verification |
| `tests/` | Core Java suites and runner |
| `android-tests/` | Separate device/emulator instrumentation |
| `setup/capture-owner.py` | Initial owner capture/import helper |
| `setup/web/` | Static Chrome/USB owner wizard, pinned browser ADB bundle, recovery and security tests |

## License and attribution

Application source is MIT; see [LICENSE](LICENSE) and [NOTICE](NOTICE). Protocol research references include [pepperonas/navee-st3-pro](https://github.com/pepperonas/navee-st3-pro); its attribution is retained. The user-supplied UT5 MAX photo and optional map content have separate attribution and are not relicensed as MIT source. OEM decompiled files, phone reports, private recordings, owner setup files, and release signing keys are excluded.
