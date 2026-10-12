# Navee Offline

A native Android companion for an already activated, owner-bound **NAVEE UT5 MAX**. Bluetooth connection, lock/unlock, scooter settings, diagnostics, and ride recording work locally. An optional OpenStreetMap street layer uses internet access; controls and recordings require no server or account. Route geometry is drawn locally even when map tiles are unavailable.

Current application version: **0.5.0**. Independent project; not affiliated with NAVEE.

## What is here

- **Home:** automatic connection to the last verified scooter and a large confirmed-state lock switch.
- **Scooter:** compact grouped settings for riding, battery, dashboard/sounds, and security.
- **Lights:** headlight, automatic lighting, line lights, and star lights together.
- **Stats:** battery, electrical, ride, and diagnostic readings with freshness and availability indicators.
- **Rides:** persistent scooter readings plus optional GPS, barometer, and experimental motion. Route maps show synchronized power, estimated grade, and speed-derived acceleration; compare rides and export raw CSV, joined CSV, JSON, or GPX.
- **More:** profiles, owner setup, security options, app preferences, and experimental settings.

The named controls target the US UT5 MAX, model/PID 2538. Other models, regions, and firmware may differ. [FEATURES.txt](FEATURES.txt) and [VERIFICATION.txt](VERIFICATION.txt) distinguish implemented behavior from hardware checks.

## Build

This is a Java Android project with an SDK-only shell build, rather than a Gradle project. No Gradle dependency downloads are needed. Use macOS, Linux, or a suitable shell environment with:

- Android SDK platform **36**, build-tools **36.1.0**, and platform-tools for installation.
- A JDK supporting `javac --release` (JDK 17 or newer recommended).

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

They cover framing/authentication, telemetry decoding, named commands, owner identity parsing, ride persistence/energy integration, and phone route quality/continuity. GitHub Actions runs these suites on pushes and pull requests.

For the separate Android instrumentation runner, build the main app first, then run:

```sh
bash android-tests/build.sh
```

Read [CONTRIBUTING.md](CONTRIBUTING.md) before using instrumentation: some parked-validation modes deliberately change scooter settings. The launch runner is restricted to an empty emulator installation.

## First setup and everyday use

Each owner needs their own Bluetooth owner identity from their already-bound NAVEE account. The current first-capture method uses a computer and ADB; daily use does not. No owner credentials are embedded in this repository or APK.

See [SETUP.txt](SETUP.txt) for the capture helper and private backup/import instructions, and [README.txt](README.txt) for detailed app behavior and recording interpretation.

## Phone recordings and maps

Start from **Rides → Start recording** while the app is visible. Choose precise location for a useful route; denying location still permits scooter and motion recording. GPS and phone sensors stop when the recording stops. The usual ten-second idle background timeout is unchanged.

The phone journal is separate from the scooter energy accumulator. GPS continues through a Bluetooth gap. Missing or poor fixes split the route; grade uses a distance window, preferably barometric elevation, with stricter limits for GPS altitude. GPS and scooter speed derivatives are separate from the phone's raw and gravity-free acceleration. Pocket movement, vibration, pressure changes, and GPS error remain visible limitations. None of these measurements establishes battery capacity.

In a saved ride choose **Route & phone sensors**. Tap the route to inspect a reading; switch colors between power, grade, and GPS acceleration. **Street map** loads only the area you view from the configured HTTPS tile provider. It starts off, requires no map account or API key, caches normal visits, and never downloads a city or entire route for offline use. The default is [OpenStreetMap](https://www.openstreetmap.org/copyright), subject to its [tile policy](https://operations.osmfoundation.org/policies/tiles/) and [privacy policy](https://osmfoundation.org/wiki/Privacy_Policy). Map requests disclose the viewed area and IP address to that provider; scooter credentials and journals are not uploaded. Replace the provider under **Rides → Phone sensors → Map source**.

Old recordings have no phone route to reconstruct. Exported route/phone files contain coordinates; raw motion includes device axes, gravity-free acceleration, earth-frame acceleration when orientation is available, and rotation quaternion. GPX exports positions/time only because Android GNSS altitude is referenced to an ellipsoid; altitude and its accuracy remain in CSV/JSON. Raw journals remain available if a large file cannot be previewed.

## Known follow-up work

- The friend has confirmed that **0.4.1 opens on Samsung**. The v0.5.0 phone sensors still need real outdoor rides on the Pixel and Samsung; emulator fixtures do not validate physical GPS/barometer/IMU accuracy.
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

MIT; see [LICENSE](LICENSE) and [NOTICE](NOTICE). Protocol research references include [pepperonas/navee-st3-pro](https://github.com/pepperonas/navee-st3-pro); its attribution is retained. OEM decompiled files, phone reports, private recordings, owner setup files, and release signing keys are excluded.
