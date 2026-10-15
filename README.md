# Volt

Your scooter, your connection. A native Android companion for the **NAVEE UT5 MAX**, with local Bluetooth controls, a customizable dashboard, and ride and battery insights.

**Volt is an independent community project. It is not affiliated with, endorsed by, sponsored by, or supported by NAVEE or its app vendor.** NAVEE and its product names identify compatible hardware; they do not imply an official relationship.

[Download the APK](https://github.com/JJRicks/navee-offline/releases/latest) · [Owner setup](https://pairscooter.jjricks.com/) · [User guide](docs/user-guide.md) · [Changelog](CHANGELOG.md)

Current release: **0.13.0**. Public v1.0 preparation is in progress; this branch is not a v1.0 release. The manufacturer-sourced scooter photo is a temporary placeholder pending replacement with a maintainer-owned photo. See the [public-release checklist](docs/public-release.md).

## What Volt does

- **Local control:** connect, lock/unlock, and adjust supported riding, lighting and charging settings without a cloud login for everyday use.
- **Your dashboard:** movable, resizable widgets, live trends, gauges, quick controls, separate layouts for different screen widths, and three saved layout slots per scooter.
- **Live diagnostics:** searchable readings and charts, favourites, battery power, temperatures, odometer and timestamped last-seen values.
- **Ride recording:** scooter telemetry plus optional GPS, barometer and experimental motion data; full-screen route maps, inspectable charts and CSV/JSON/GPX exports.
- **Battery insights:** charge recordings, charging-time estimates and an experimental usable-energy estimate based on recorded rides.
- **Local ownership:** multiple scooter profiles, dashboard import/export and password-encrypted full backups.

Bluetooth controls and recordings work without a Volt server. Optional map tiles need internet access; route geometry can still be drawn locally without them. The map provider receives requests for the viewed area. See [Privacy](PRIVACY.md) for storage, permissions and network behavior.

## Compatibility

| Requirement | Support |
|---|---|
| Phone | Android 8.0+ with Bluetooth LE |
| Scooter | Already activated, owner-bound **US UT5 MAX (model/PID 2538)** |
| First setup | NAVEE already signed in and working with your scooter |
| Other models, regions or firmware | Not established; fields and commands may differ |

Volt does not activate a new scooter, bypass ownership, or replace NAVEE account recovery. Firmware changes can affect setup and compatibility. The electronic lock depends on the scooter's own behavior; Volt displays confirmed state and marks disconnected information as stale.

## Get started

1. Download the APK from [this project's Releases](https://github.com/JJRicks/navee-offline/releases/latest) and install it. An update signed with the same project key retains app data.
2. Have your phone and powered-on, safely parked scooter nearby. Confirm the NAVEE app can connect to it.
3. Follow Volt's onboarding and choose an owner-setup method:
   - **Chrome/USB:** use [pairscooter.jjricks.com](https://pairscooter.jjricks.com/) on a computer with desktop Chrome or Edge and a USB data cable. [Details and self-hosting](setup/web/README.md).
   - **Shizuku on the phone:** Android 11+ with shell-backed Shizuku 13+. [Setup guide](setup/SHIZUKU.md).
   - **Computer ADB:** use the included capture helper. [Instructions](SETUP.txt).
   - **Existing setup or full backup:** import it through onboarding.
4. Approve the requested permissions, close NAVEE after setup so it releases Bluetooth, and connect in Volt.

The capture helpers require broad Android debugging access with explicit consent. They process the Bluetooth owner identity locally; no NAVEE or Google password is entered into Volt or the website. The browser helper currently supports the main personal Android user only. Helpers remain compatibility-sensitive; read their cleanup and recovery instructions before use.

Everyday use needs no computer, ADB or Shizuku. Launch can reconnect to the last scooter automatically. Opening the app never automatically locks or unlocks it.

## Data and backups

Readings, routes and profiles stay in Android app storage. **More → App preferences → Dashboard backups** exports layouts without owner keys or telemetry; custom titles and notes are included. Developer options also provide password-encrypted full backups, including private owner setup and recordings. Keep those files private and retain their password.

Before clearing app data or replacing an installation with a differently signed build, export a backup. Android permissions and Shizuku authorization must be granted again after a restore. See the [user guide](docs/user-guide.md#back-up-and-restore) and [backup format](docs/app-data-backup.md).

## Build from source

Volt uses **Java and native Android views**, with an SDK-only shell build. There is no Gradle project. Install JDK 17+, Python 3, Android SDK platform **36**, and build-tools **36.1.0**. Platform-tools are needed to install on a device.

```sh
git clone https://github.com/JJRicks/navee-offline.git
cd navee-offline
export ANDROID_SDK_ROOT=/path/to/Android/sdk
export JAVA_HOME=/path/to/jdk
python3 setup/shizuku-dependencies.py --fetch
bash build.sh
```

Dependencies are explicitly fetched once and verified against pinned hashes; subsequent Android builds work offline. The APK is written to `dist/navee-offline-lock.apk`. The optional browser helper has its own [Node/npm build](setup/web/README.md#build-and-local-use).

A fresh clone generates a **local signing key**, which cannot update a project release signed with a different key. Keep that key private. Build output, keys and optional `local-build.env` are ignored by Git. Paths can be overridden with `NAVEE_BUILD_DIR`, `NAVEE_KEY_DIR` and `NAVEE_APK_PATH`; see [Contributing](CONTRIBUTING.md).

## Tests and contributions

Core tests need a JDK and Python, with no Android SDK or device:

```sh
bash tests/run.sh
python3 tests/owner-capture-guard-test.py
```

Build the separate native instrumentation APK with `bash android-tests/build.sh`. Read the [development notes](docs/development-notes.md) before running it: emulator fixtures clear test data, and parked-scooter modes can deliberately change settings.

Bug reports, compatibility reports and pull requests are welcome. Start with [Contributing](CONTRIBUTING.md). For vulnerabilities or exposed owner credentials, follow [Security](SECURITY.md) instead of posting sensitive details publicly.

## Measurement limits

Reported battery health and rated capacity are scooter readings, not independent capacity tests. The capacity experiment, charging ETA, hill grade and phone acceleration are estimates affected by coverage, temperature, load and sensor quality. An unverified energy counter is explicitly labelled and is not used to prove battery capacity. Missing measurements remain gaps.

Tests cover protocol handling, storage, layout and lifecycle behavior; they do not validate every phone, scooter, firmware or riding condition. [Verification history](VERIFICATION.txt) records what was actually exercised. [The user guide](docs/user-guide.md) explains the readings and their limits.

## License and acknowledgements

Application source is under the [MIT License](LICENSE). Third-party components, fonts, maps and the placeholder scooter photo have separate terms described in [NOTICE](NOTICE). The photo's redistribution permission has not been established; it must be replaced or cleared before a public release.

Protocol research builds on [pepperonas/navee-st3-pro](https://github.com/pepperonas/navee-st3-pro), with its attribution retained. Thank you to the project's testers and contributors.

Volt was previously named **Navee Offline**. The repository name, Android package and data formats remain compatible with existing installations. Volt is community software, not an official NAVEE application.
