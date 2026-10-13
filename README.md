# Navee Offline

A native Android companion for an already activated, owner-bound **NAVEE UT5 MAX**. Bluetooth connection, lock/unlock, scooter settings, diagnostics, and ride recording work locally. An optional USGS Topo basemap uses internet access; controls and recordings require no server or account. Route geometry is drawn locally even when map tiles are unavailable.

Current application version: **0.7.2**. Independent project; not affiliated with NAVEE.

Download the signed APK from [GitHub Releases](https://github.com/JJRicks/navee-offline/releases/latest). Install it over the earlier official APK to keep scooter setup, dashboards, and recordings. See [CHANGELOG.md](CHANGELOG.md) for release changes and [RELEASING.md](RELEASING.md) for the release process.

## What is here

- **Home:** an editable dashboard for each scooter, with automatic connection, a large lock switch, battery overview, charts, and shortcuts.
- **Scooter:** compact grouped settings for riding, battery, lights, dashboard/sounds, and security. Rename your scooter here or use its widget's pencil while editing Home.
- **Stats:** battery, electrical, ride, and diagnostic readings; grey last-known values retain their measurement times after disconnection or restart.
- **Rides:** persistent scooter readings plus optional GPS, barometer, and experimental motion. Route maps show synchronized power, estimated grade, and speed-derived acceleration; compare rides and export raw CSV, joined CSV, JSON, or GPX.
- **More:** profiles, owner setup, security options, app preferences, experimental settings, and connected-history storage controls. Submenus are remembered for one minute away from the tab or app; a longer absence returns to the More index.

The named controls target the US UT5 MAX, model/PID 2538. Other models, regions, and firmware may differ. [FEATURES.txt](FEATURES.txt) and [VERIFICATION.txt](VERIFICATION.txt) distinguish implemented behavior from hardware checks.

## Make Home your own

Tap the **pencil** to edit Home. Drag the top-left dots to move a widget, or drag its visible lower-right corner to resize it; a long press on the dots also starts a move. A dashed outline previews the full destination while neighboring cards slide aside and fill empty rows. Releasing saves the preview; cancelling restores the starting arrangement. Tap **Done** to use scooter controls again.

The **gear** is the single entry to widget options. For a drag-free alternative, tap the dots for **Move** or the corner for **Size**; both dialogs also remain in the options menu. Widget options keep **Duplicate** and a red **Remove widget** button side by side at the bottom, with an **X** in the top-right corner to close. The actions stay visible while settings scroll. Widget menus, handles and the scooter-name pencil appear only while global dashboard editing is on. Dialogs follow the app's light/dark theme. Layouts are saved per scooter and separately for each column count; **Layout → Columns** offers adaptive or one to four columns, limited by the screen width.

Editing keeps each widget and its content at the same dimensions as normal viewing. Translucent controls float over the card, so entering or leaving edit mode does not expand cards or rearrange their contents.

The 15 choices are Lock & connection, Your scooter, Battery overview, Big reading, Gauge, Live trend, History chart, Reading list, Trip overview, Battery health, Capacity experiment, Charging, Quick access, Ride recorder, and Note. Customize titles, parameters, reading lists, colors, chart windows, and gauge ranges where applicable. Up to 32 widgets are allowed, with one lock widget. The six defaults include the lock, a UT5 MAX photo for a recognized model that stays visible when disconnected, battery charge with estimated range/power/reported health/temperature, a 15-minute power chart, the capacity experiment, and recording controls. Quick access remains available in Add widget. While the scooter freshly reports charging, the battery card replaces its normal details with charging power, current and estimated time remaining, keeping the same battery percentage and ring. Existing custom layouts are preserved; add new widgets through Edit or restore defaults.

New dashboards and **Layout → Restore default widgets** use the saved phone/foldable preset. At two columns, Your scooter spans the top, lock and battery sit side by side, followed by Live trend, Capacity experiment, and Ride recorder. At four columns, the scooter card takes three columns beside the lock; power and battery share the next row, with recording below the power chart and the capacity experiment across the bottom. One- and three-column variants fit other screen widths. Existing custom dashboards, including empty ones, retain their saved widgets and layouts.

**Your scooter** places a larger photo on the left and stacks configurable readings vertically on the right, defaulting to just odometer and drive mode. These two values grow into the available space, with a moderate size cap and automatic shrinking in compact cards. Reported charges and battery throughput remain in Stats and can still be selected for a custom widget. The former four-reading default is updated once to these two readings; other custom lists and saved card positions/sizes are preserved. New scooter cards use four rows and can shrink to three. Use the pencil beside its name while editing Home to rename the local profile. On the UT5 MAX (PID 2538), owner-observed mode codes 11, 2, and 3 display as WALK, ECO, and SPORT; other codes and models retain raw labels. Raw diagnostics and ride exports remain unchanged. The battery ring uses a compact **%** label with more room around the number.

Live SPORT is pale blue in the dark theme and a readable darker blue in the light theme; ECO is green, and WALK uses a green pedestrian icon. Last-known modes stay grey, with their mode names retained for accessibility.

Card contents adapt as you resize: rows stack at narrow widths, values fit within readable font limits, and photos, rings, gauges and charts use the available space. Short content stays balanced within its card instead of gathering at the top; saved card positions and sizes stay intact. Swipe anywhere within overflowing widget content to scroll it. Your scooter, Lock & connection, Gauge and Ride recorder have three-row minimums to keep their content usable.

The battery overview gives its large centered percentage ring a thicker arc and balanced room within the card, with range, power, reported health and temperature represented by route, lightning, heart and thermometer icons along the bottom. The default four readings share a row where they fit; narrow cards or large text wrap them rather than hide values. The extra “Battery” label beside the ring and repeated “Live” footers are removed. Last-known readings still stay grey and retain their timestamps.

Lock & connection is a full-width bar: tap anywhere on it or slide it to lock or unlock. When locked, the white knob sits on the right; when unlocked, the red knob sits on the left. While disconnected or connecting, the card shows the last verified lock time with a Connect or Cancel button instead. The connection line in each page header shows Connected, Connecting or Disconnected with the last-seen time; tap it for details and to connect or disconnect. **More → App preferences → Time format** follows the phone by default, or selects 12-hour/24-hour clocks for displayed times.

The existing Live trend widget supports a **2 minutes** window, including for battery power. Redundant “signed” and “Recent readings • live” labels are removed from the chart presentation; the underlying values and recorded sign remain unchanged. Stats also omits repeated instructions while retaining technical explanations that affect interpretation.

Scooter, Lights, Stats, and the widget parameter picker’s search fields release focus when the keyboard is dismissed or you tap outside, keeping the current filter. Consistent inner padding and space between search, filters and results make these controls easier to scan.

Charts need numeric readings actually supplied by the scooter. Unsupported or text-only selections have no numeric graph. Reported battery health is the controller's value, **not a measured capacity test**.

## Capacity experiment

The **Capacity experiment** widget compares recorded electrical energy with the battery gauge's percentage drop: `100 × summed Wh ÷ summed percentage points used`. It analyzes the selected scooter's explicit ride recordings, including the current recording when it qualifies, and refreshes about every ten seconds while viewed. The active ride replaces its previous contribution. It starts no recording, connection, GPS, or sensors of its own.

With current direction confirmed throughout each accepted ride, the widget uses net energy drawn minus returned. Rides with unconfirmed direction form a **separate absolute-throughput proxy**; that counts both draw and return and may overstate energy depletion. The two groups never mix, and each needs at least 20 battery percentage points before showing a result. The percentage beside either result compares it with the advertised energy; **it is not battery health or a measured capacity percentage**.

Each ride must last at least one minute, include 30 seconds of measured movement, use at least five battery percentage points, and have at least 95% electrical coverage. Battery and power readings must align with both ends. Gaps, recovery, detected clock/direction changes, missing end readings, and visible gauge rebounds above one point exclude a ride. Sampled trends can miss brief rebounds. These are experimental screening rules, not a validated capacity test. Record ordinary riding without charger input.

The window defaults to **90 days**, with 7, 30, 90, 180, 365, and 730 days available. Older rides fall out of the calculation without being deleted. Set a custom advertised energy in Wh in the widget's options; zero uses the known model rating. The UT5 MAX reference is **716.04 Wh (46.8 V × 15.3 Ah)** from [NAVEE's US specifications](https://naveetech.us/products/ut5-max). Other models need an entered reference for a percentage comparison.

Cold, load, voltage range, gauge rounding and recalibration can shift this estimate. State of charge can represent charge rather than energy, so different sections of the same healthy pack can produce different Wh equivalents. The widget reports available pack temperatures and flags near-full/near-empty readings without applying a correction. [Texas Instruments' battery-gauging comparison](https://www.ti.com/lit/an/sluaar3/sluaar3.pdf) explains why proper capacity learning also accounts for gauge behavior and operating conditions; it is an engineering reference, not evidence about which gauge NAVEE uses. This experiment cannot prove that the pack is undersized.

## Charging readings

The battery overview automatically switches its normal details to charging power, current and a rough remaining time while the scooter freshly reports charging. The battery percentage and ring remain visible; normal details return when charging is no longer freshly reported. The separate **Charging** widget is also available. Power is the magnitude of paired battery voltage and current while charging. It measures the **battery side**, not outlet consumption or charger efficiency.

Remaining time uses reported rated Ah, the battery percentage still needed, and the current charging rate. A known charge limit below 100% becomes the target, with a reminder that reaching full requires raising it. An unknown limit is identified explicitly; a displayed 100% estimate assumes charging continues. Missing, stale or unpaired inputs leave the affected reading unavailable. Very low current still permits power/current readings but no time estimate. Charging normally slows near full, so this present-rate estimate may be optimistic; it is not a promised completion time or a capacity test.

## Last-known readings and connected history

Last-known stats and settings stay on the phone for the selected scooter. Grey values are historical; a grey lock state is unverified until you reconnect. Only fresh authenticated scooter state enables setting changes.

Connected history starts enabled and saves scooter readings while connected, without starting a ride, GPS, or phone sensors. Its total storage budget is **50 MiB** across profiles: detailed samples for up to seven days, minute summaries for up to 30 days, and daily summaries for up to two years. Older samples can be trimmed sooner to stay within the budget. Use **More → Data & history** to inspect storage, stop new timeline samples, or clear one scooter's history or all history. Turning history off retains the last-known cache; clearing removes both its selected timelines and cache. Ride recordings and owner setup are separate.

The usual ten-second idle background timeout is unchanged. History does not prolong a connection or turn an ordinary connection into a ride recording.

## Lights

All lighting controls are under **Scooter → Lights**. The former “Headlight” switch is correctly named **Tail light**: OEM code identifies that command as the tail-light setting. **Automatic lights** uses ambient brightness to control the headlight and tail light. The app confirms configuration readback, not visible light output. No separate main-headlight command is verified; use the scooter's power button for manual front-light control. The OEM describes manual headlight changes as temporarily suspending automatic control until the scooter restarts.

## Build

This is a Java Android project with an SDK-only shell build, rather than a Gradle project. No Gradle dependency downloads are needed. Use macOS, Linux, or a suitable shell environment with:

- Android SDK platform **36**, build-tools **36.1.0**, and platform-tools for installation.
- A JDK supporting `javac --release` (JDK 17 or newer).

```sh
git clone https://github.com/JJRicks/navee-offline.git
cd navee-offline
export ANDROID_SDK_ROOT=/path/to/Android/sdk
export JAVA_HOME=/path/to/jdk
bash build.sh
```

The APK is written to `dist/navee-offline-lock.apk`. The build checks its signature and alignment. Temporary build files and a new local signing key live in ignored `.local/` directories.

**Signing matters:** a fresh clone generates its own signing key. It cannot update an installed APK signed with a different key. Preserve the original key privately for release updates that retain local app data. Contributors can submit source changes without possessing that release key. Uninstalling the app removes private profiles and recordings; export them before replacing an installation.

Build paths can be overridden with `NAVEE_BUILD_DIR`, `NAVEE_KEY_DIR`, and `NAVEE_APK_PATH`. The optional ignored `local-build.env` accepts local shell assignments. Custom signing uses `NAVEE_SIGNING_KEYSTORE`, `NAVEE_SIGNING_ALIAS`, and `NAVEE_SIGNING_PASSWORD`. Do not commit local configuration or signing material.

## Test

Run the existing core suites without an Android device or Android SDK:

```sh
bash tests/run.sh
```

They cover framing/authentication, telemetry decoding, named commands, owner identity parsing, ride persistence/energy integration, phone route quality/continuity, and dashboard placement. GitHub Actions runs these suites on pushes and pull requests.

For the separate Android instrumentation runner, build the main app first, then run:

```sh
bash android-tests/build.sh
```

Read [CONTRIBUTING.md](CONTRIBUTING.md) before using instrumentation: some parked-validation modes deliberately change scooter settings. The launch runner is restricted to an empty emulator installation.

## First setup and everyday use

Each owner needs their own Bluetooth owner identity from their already-bound NAVEE account. The current first-capture method uses a computer and ADB; daily use does not. No owner credentials are embedded in this repository or APK.

On first launch the app opens a step-by-step setup: choose to capture an identity, import a backup file or enter a 12-character identity, then follow one short step per screen through USB debugging, the capture helper, import, Nearby devices and connecting. The tab bar appears once it is finished. **More → My scooters → Run setup again** reopens it.

See [SETUP.txt](SETUP.txt) for the capture helper and private backup/import instructions, and [README.txt](README.txt) for detailed app behavior and recording interpretation.

## Phone recordings and maps

Start from **Rides → Start recording** while the app is visible. Choose precise location for a useful route; denying location still permits scooter and motion recording. GPS and phone sensors stop when the recording stops. The usual ten-second idle background timeout is unchanged.

The phone journal is separate from the scooter energy accumulator. GPS continues through a Bluetooth gap. Missing or poor fixes split the route; grade uses a distance window, preferably barometric elevation, with stricter limits for GPS altitude. GPS and scooter speed derivatives are separate from the phone's raw and gravity-free acceleration. Pocket movement, vibration, pressure changes, and GPS error remain visible limitations. None of these measurements establishes battery capacity.

In a saved ride choose **Route & phone sensors**. Pinch to zoom around your fingers, drag to pan, or use the zoom and Fit buttons. Tap the route to inspect a reading; switch colors between power, grade, and GPS acceleration. **Basemap** loads only the area you view. It starts off and caches ordinary visits. The default is **USGS Topo**, a U.S. street/terrain map requiring no account or key. [USGS map-service terms](https://www.usgs.gov/faqs/what-are-terms-uselicensing-map-services-and-data-national-map) permit public use. Attribution is shown beside the map. Close zooms enlarge the available level-16 tiles instead of requesting unsupported detail.

Use **Map source** directly in the route viewer or under **Rides → Phone sensors** to select USGS, OpenStreetMap, or a custom HTTPS source and its attribution. Existing custom sources are preserved. OpenStreetMap remains subject to its [tile policy](https://operations.osmfoundation.org/policies/tiles/); a reproduced policy rejection does not establish which server rule triggered it. The app retains its honest identifying header, stops repeated denied requests, and rejects `X-Blocked` error pictures even when HTTP reports success. Old unverified cache entries require a successful refresh before display, so a cached block picture cannot reappear as a map. Routes remain visible when the basemap is unavailable.

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

## License and attribution

Application source is MIT; see [LICENSE](LICENSE) and [NOTICE](NOTICE). Protocol research references include [pepperonas/navee-st3-pro](https://github.com/pepperonas/navee-st3-pro); its attribution is retained. The user-supplied UT5 MAX photo and optional map content have separate attribution and are not relicensed as MIT source. OEM decompiled files, phone reports, private recordings, owner setup files, and release signing keys are excluded.
