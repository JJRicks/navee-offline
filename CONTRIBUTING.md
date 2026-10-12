# Contributing

Create a branch from `main`, make the change, and open a pull request with the behavior changed and the checks performed. Keep changes focused so they can be reviewed and tested on each phone.

```sh
git switch -c your-change
bash tests/run.sh
bash build.sh
```

The core Java tests require a JDK; the APK build additionally needs SDK platform 36 and build-tools 36.1.0. A fresh clone builds with its own ignored local key. Source contributors do not need the private release signing key.

## Device checks

`android-tests/ParkedValidation.java` is a separate, explicitly invoked instrumentation runner. Its modes include real lock/settings writes and stationary recording sessions; choose a mode only when those actions are intended and the owner is present with a safely parked scooter. Navigation/background modes perform different checks and have documented guards. Read the chosen mode before running it.

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
count; widget scale values are stored in canonical units and converted for display.
