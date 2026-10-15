# Native haptic regressions

These instrumentations are part of the existing `parked-validation.apk`; no
second probe APK or production instrumentation hooks are needed. Use a disposable
API 34+ Android emulator with CLICK, THUD and TICK vibration primitives. The
fixtures were exercised on the API 36 Google APIs emulator. They inspect Android's
vibrator diagnostics, so a changed Android diagnostic format fails visibly and
may require updating the parser.

`run-haptics.py` refuses physical-device serials and checks the emulator and boot
properties **before changing anything**. It removes the emulator's existing Volt
app/runner, installs the supplied APKs, clears app data between cases, and disables
Bluetooth. Each native fixture independently refuses physical hardware, existing
data, enabled Bluetooth or missing permissions. All profiles and readings are
synthetic, and the tests assert that no Bluetooth transport or recording starts.

Build both APKs with a disposable signing identity (not the release key):

```sh
export NAVEE_BUILD_DIR="$PWD/.local/haptic-tests/build"
export NAVEE_KEY_DIR="$PWD/.local/haptic-tests/keys"
export NAVEE_APK_PATH="$PWD/.local/haptic-tests/volt-test.apk"
# Override any private local-build.env release identity.
export NAVEE_SIGNING_KEYSTORE= NAVEE_SIGNING_ALIAS=navee-offline NAVEE_SIGNING_PASSWORD=
bash build.sh
bash android-tests/build.sh
python3 android-tests/run-haptics.py \
  --adb "$ANDROID_SDK_ROOT/platform-tools/adb" \
  --serial emulator-5554 \
  --app "$NAVEE_APK_PATH" \
  --runner "$NAVEE_BUILD_DIR/parked-validation/parked-validation.apk" \
  --out "$PWD/.local/haptic-tests/results"
```

Replace the serial with the explicitly chosen disposable emulator. The runner
accepts `--case quick` or `--case connection` for a focused rerun. It uses `finally`
to clear synthetic app data and restore the prior system haptic
setting/Bluetooth state on pass, assertion failure or instrumentation error.
The native fixtures also restore system haptics and stop their activity/service
in their own `finally` blocks. Do not use these tests on an emulator whose Volt
installation you want to preserve. A disconnected or terminated emulator may
prevent cleanup; restart the emulator and clear its disposable app installation
before reusing it.

The output directory contains a JSON summary, native assertion reports, and
before/after vibrator diagnostics for each scenario. A missing success marker,
zero assertions, failed fixture, or cleanup failure returns a nonzero exit code.

## Coverage

- **Quick access:** actual pointer taps produce exactly one directional toggle
  effect for each accepted on/off request. Display refresh, non-toggle menus,
  pending commands, dashboard editing and disconnected controls produce none.
  App and Android haptic opt-outs silence feedback without preventing the control
  change. The test uses the app's simulator, never a scooter.
- **Connection player:** connect CLICK/THUD scales, fading disconnect TICK scales,
  cancellation and replacement, app/system/view opt-outs, preference changes
  during an effect, detached views, pause/resume, destruction and simulation.
- **Real focus lifecycle:** a real Android dialog cancels an in-progress effect,
  suppresses new effects and consumes a hidden disconnect. Dismissal restores
  focus without replaying the hidden cue.
- **Connection observer:** unauthenticated READY is silent, an observed disconnect
  plays once, repeated refreshes remain silent and hidden changes do not replay.
  The activity's own observer/player also exercises a repeated loss inside the
  five-second quiet period, expiry without replay, and a later loss cue using
  the real elapsed clock. Only previous observer state is seeded; no GATT object
  or transport is fabricated. `tests/ConnectionFeedbackStateTest.java` separately
  covers exact boundaries and reconnect sequences with a fake elapsed clock.

These tests prove requested effects and lifecycle behavior, not how the vibration
motor feels. Older-device fallback effects and actual tactile strength still need
physical hardware checks.
