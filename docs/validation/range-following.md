# Range follows riding mode

The former “Drive mode” option selected the firmware's fixed D estimator (0x56
value2). It was not an automatic-follow option. The OEM selector exposes D/S or
E/D/S depending on its capability report.

The app now defaults each scooter profile to **Follow riding mode**. A fresh,
authenticated UT5 MAX connection selects the supported Sport estimator for Sport
and Eco for Eco/Walk. Firmware without an Eco estimate uses its D estimator for
Eco/Walk; the picker explains that limitation. Unknown models, unknown mode
codes, stale values and unavailable firmware options trigger no automatic write.
The app never rescales a distance. Every widget and Live Stats continues to use
the same reported `remaining_range_km` value after the scooter updates it.

Fixed choices disable following for that profile, including choosing the
already-active fixed model. The local Follow preference can be restored in the
same picker. Pending mutations are respected, and each mode is attempted only
once per connection/mode transition to avoid retry loops after a failure.
Existing command acceptance and readback checks are retained.

Validation:
- 28 pure policy assertions cover D/S and E/D/S, Sport/Eco/Walk, unsupported
  modes/models/options, freshness, busy state, fixed choices, reconnects,
  profile changes, matched readback and no repeated attempts.
- The full pure test suite passes (46,247 checks/assertions).
- Production and instrumentation APK builds verify signing and alignment.
- Final native emulator integration passes 712 assertions at 411x840 dp. It
  verifies the new-profile default, Sport selection, fixed-D retention, re-enabling
  Follow, and the existing simulation isolation/background/cache checks.

Physical limitation: no real scooter mode changes were exercised for this PR.
Actual range remains an estimate supplied by the scooter, not measured capacity.
