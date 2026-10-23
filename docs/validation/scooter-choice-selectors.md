# Scooter choice selector sweep

Update note 16's remaining short scooter choices now use the shared rounded sliding selector:

- Line and star light patterns retain their inline placement below the label.
- Short fixed choice sheets (two to five labels up to 18 characters) use the same track. This includes energy recovery, scooter display units, maximum speed, charge ceiling, lock duration, and prompt sound where supported.
- Firmware-dependent range estimates and long explanatory labels retain their readable list. Color choices remain swatches. The variable scooter profile list remains a list. Stats tabs, Rides controls, app preferences, and dashboard graph controls are covered by their separate PRs.

Scooter readback owns the highlight. The click callback immediately restores the previously reported value before attempting a change; a pending write disables the whole track, keeps the confirmed value highlighted, and shows the existing waiting feedback. Rejections preserve that value and their error text. An unknown setting has no highlighted choice. The existing firmware command validation remains in `BleController`; no command bytes or protocol behavior change.

`SegmentedSelector.select(-1, ...)` now clears its indicator and accessibility selection. Disabling the selector also disables its child targets and guards click dispatch, including programmatic clicks. Preference selectors retain their usual immediate selection behavior. The two inline light-pattern tracks opt into fit-or-wrap layout: full labels use the current font size and padding, wrap at word boundaries, and use another row when needed. Every option stays visible without horizontal scrolling, with 48 dp minimum targets and the existing sliding highlight. Other selector layouts are unchanged.

## Validation

`bash tests/run.sh`: all 46,219 pure checks pass. Signed app and native runner builds pass.

`DeveloperSettingsValidation` now additionally covers both light tracks, unknown values, disabled clicks, simulated disconnect races, pending writes, rejected changes, new readback selection, and choice-sheet completion. The review regressions check that every inline pattern option stays within the track, has padded/unclipped text and a full touch target, and never breaks a word between lines. These states are injected only into the fresh emulator's in-memory simulator; no Bluetooth transport, command queue, recording, real owner identity, or persistent simulation samples are created. Existing preference, developer gate, simulation lifetime, database/cache preservation, and service recreation checks still run.

Passed 844 native assertions at each of 344×882 dp with both 100% and 140% system font, 360×800 dp with 140% system font, and 725×604 dp with 100% system font. The compact and large-font tracks were inspected visually using native window renders, which exclude unrelated Android toast windows from the captures.

Native validation uses the existing runner on an empty disposable emulator with Bluetooth disabled and Nearby devices permission already granted:

```sh
adb -s emulator-5590 shell am instrument -w -r \
  com.jjricks.volt.tests/com.jjricks.volt.tests.DeveloperSettingsValidation
```

Do not use the owner's phone for this fixture. It refuses physical hardware and pre-existing private data. Actual scooter writes/readbacks require a later parked-device check; this PR does not install on the owner's phone or deploy anything.
