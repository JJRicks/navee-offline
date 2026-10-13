# Optional phone-only owner capture

Android 11+ owners can use **Use Shizuku on this phone** in the setup wizard.
Install/start Shizuku from its [official guide](https://shizuku.rikka.app/guide/setup/),
using wireless debugging, then return to Volt. Shizuku 13+ and shell UID 2000 are
required. This first implementation supports the primary Android user (user 0) only,
and explicitly scopes NAVEE launch/force-stop there. Work and secondary profiles
fail before any logging change. A bounded Android-user enumeration also rejects
NAVEE installations in other profiles, because the logging property is device-global.
The launcher is resolved to an exact NAVEE component before it is started. Root-backed Shizuku is intentionally not used by this helper.

Granting Shizuku permission gives Volt broad Android debugging authority, not a
restricted "read one scooter" permission. The permission screen explains this.
The next screen separately asks for ownership/authorization and permission to
restart NAVEE and capture. The phone must already be signed into NAVEE, with a
parked scooter it can connect to. No NAVEE cloud sign-in is implemented here.

## Data and import

The UserService exposes only start, cancel and recovery calls to Volt's app UID.
It takes a random session token, not arbitrary commands, package names or paths.
It resolves NAVEE's package UID for the current Android user and uses device-side
`logcat --uid`, tag and message filters. Unsupported UID filtering fails closed.
Only new phone-timestamped, checksum-valid 17-byte auth frames are accepted;
conflicting identities/roles abort. Subprocess stdout and stderr are bounded,
stderr is discarded, and no raw capture is logged or persisted.

The captured six-byte Bluetooth identity stays in memory until the user taps
**Import owner setup** after cleanup is confirmed. It is never sent to a server,
put in an Intent, saved-instance state or clipboard. Explicit import uses the
existing private pending-import/profile storage, retaining all existing profiles
and waiting for recording/command safety conditions. No Downloads file is made.

## Cleanup and concurrency

The shell service reserves the shared mode-0700 lock directory
`/data/local/tmp/volt-owner-capture.lock` before reading the previous property.
The shell-owned files contain only the owner method, random session token,
original `log.tag.com.navee.ucaret` property, phone timestamp and guard status.
The app reserves the token privately before binding the helper and separately
commits the original property before any modification. Neither journal contains an owner identity.

A detached, fixed-purpose shell guard must acknowledge readiness before logging
is enabled. Start and cleanup share an exclusive gate. All potentially blocking
Android commands have timeouts. Capture ends after 90 seconds; cleanup runs on
success, cancellation, timeout and app-Binder death. The independent guard attempts
restoration at 120 seconds if the client/helper stops. Bounded gate waiting and
commands can add roughly 45 seconds in a failure case. Stopping/rebooting the
entire phone prevents code from running; the saved recovery screen remains the
source of truth until restoration is verified. A reboot resets temporary Android
properties, but recovery still verifies rather than assuming success.

Cleanup stops NAVEE (its logging configuration is cached), restores the exact
original property and verifies successful readback, including an empty property.
A guard acknowledges its terminal state only once, as its last side effect; failed
watchdogs remain recoverable after a separately successful cleanup. The verified
completed lock is atomically retired before deleting its non-secret files.
No identity is offered unless cleanup
succeeds. Unknown/other-session locks are never taken over or deleted. Recovery
requires explicit user action with the original matching session. Do not run the
older Python capture helper concurrently: it predates the shared lock.

The temporary service continues when NAVEE comes to the foreground; it does not
hold the scooter's ordinary Bluetooth connection open or change its ten-second
background timeout. Progress/completion callbacks are one-way so a frozen UI
cannot delay cleanup. The only synchronous callback persists the cleanup journal
before logging changes. App process death triggers cancellation, with the detached
guard as a fallback. Captured identities are not retained across process death.

## Dependencies and build

Run `python3 setup/shizuku-dependencies.py --fetch` explicitly once. This fetches
SHA-256-pinned official Shizuku API/provider/aidl/shared 13.1.5 AARs from Maven
Central and AndroidX annotations 1.3.0 from Google's Maven repository into ignored
`.local/shizuku-dependencies`. Normal `bash build.sh` verifies/extracts them offline,
generates the narrow AIDL interfaces and immutable guard source, and packages their
classes from an explicit allowlist after clearing old extraction output. No runtime JavaScript or downloaded executable code is involved.

The manifest uses the official provider's
`android.permission.INTERACT_ACROSS_USERS_FULL` protection, Shizuku's API permission
and v3 metadata. The setup Activity is unexported; provider access does not grant
other apps the capture interface. NAVEE/Shizuku package visibility queries are
explicit. Shizuku is optional at runtime; existing file/manual/desktop setup and
all everyday BLE features work without it.

- [Shizuku API guide and MIT-licensed source](https://github.com/RikkaApps/Shizuku-API)
- [Shizuku setup and device limitations](https://shizuku.rikka.app/guide/setup/)
- [AndroidX annotations (Apache 2.0)](https://developer.android.com/jetpack/androidx/releases/annotation)

## Validation

`bash tests/run.sh` exercises the pure transaction and parser with simulated
commands: strict freshness, source UID parsing/filtering, malformed/conflicting
frames, exact original properties including spaces, lock ordering, cancellation,
timeout and cleanup failures. It also verifies subprocess output/time bounds.

`python3 tests/owner-capture-guard-test.py` executes the actual guard with local
stub commands, a temporary path and a shortened timer. It checks the readiness
gate, exact restore, expired/wrong-owner sessions, signal during startup, failed
force-stop and detached watchdog restoration. It never contacts a device.

Physical NAVEE capture still needs a parked-scooter check on supported phones.
A passing synthetic emulator fixture cannot establish OEM logging availability,
Samsung battery-management behavior or wireless-debugging pairing ergonomics.
