# Navee Offline

A native Android companion for an already activated, owner-bound **NAVEE UT5 MAX**. Bluetooth connection, lock/unlock, scooter settings, diagnostics, and ride recording work locally. The app has **no INTERNET permission**.

Current application version: **0.4.1**. Independent project; not affiliated with NAVEE.

## What is here

- **Home:** automatic connection to the last verified scooter and a large confirmed-state lock switch.
- **Controls:** searchable settings grouped into Ride, Battery, Lights, Line lights, Star lights, Display, and Security.
- **Stats:** battery, electrical, ride, and diagnostic readings with freshness and availability indicators.
- **Rides:** persistent recordings, gap-aware energy analysis, comparison, CSV/JSON export, and notes.
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

The APK is written to `dist/navee-offline-lock.apk`. The build checks its signature, alignment, and absence of INTERNET permission. Temporary build files and a new local signing key live in ignored `.local/` directories.

**Signing matters:** a fresh clone generates its own signing key. It cannot update an installed APK signed with a different key. Preserve the original key privately for release updates that retain local app data. Contributors can submit source changes without possessing that release key. Uninstalling the app removes private profiles and recordings; export them before replacing an installation.

Build paths can be overridden with `NAVEE_BUILD_DIR`, `NAVEE_KEY_DIR`, and `NAVEE_APK_PATH`. The optional ignored `local-build.env` accepts local shell assignments. Custom signing uses `NAVEE_SIGNING_KEYSTORE`, `NAVEE_SIGNING_ALIAS`, and `NAVEE_SIGNING_PASSWORD`. Do not commit local configuration or signing material.

## Test

Run the existing core suites without an Android device or Android SDK:

```sh
bash tests/run.sh
```

They cover framing/authentication, telemetry decoding, named commands, owner identity parsing, and ride persistence/energy integration. GitHub Actions runs these suites on pushes and pull requests.

For the separate Android instrumentation runner, build the main app first, then run:

```sh
bash android-tests/build.sh
```

Read [CONTRIBUTING.md](CONTRIBUTING.md) before using instrumentation: some parked-validation modes deliberately change scooter settings. The launch runner is restricted to an empty emulator installation.

## First setup and everyday use

Each owner needs their own Bluetooth owner identity from their already-bound NAVEE account. The current first-capture method uses a computer and ADB; daily use does not. No owner credentials are embedded in this repository or APK.

See [SETUP.txt](SETUP.txt) for the capture helper and private backup/import instructions, and [README.txt](README.txt) for detailed app behavior and recording interpretation.

## Known follow-up work

- Confirm the **0.4.1 Samsung Android 16 startup fix** on affected hardware. Fresh-emulator launch, permissions, theme changes, and Activity recreation checks pass; direct Samsung confirmation is pending.
- Investigate the telemetry field `energy_throughput_wh`, currently displayed as **Lifetime energy throughput**. Observed values can decrease on this firmware, so its lifetime-counter meaning is unverified. Do not use its delta for battery-capacity conclusions. RideStore's sampled voltage/current integration is separate.
- A short real ride recording has been checked for electrical integration consistency. A controlled full usable-capacity or range test remains outstanding. Reported health and rated Ah are not capacity measurements.

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
