# Preferences and charging labels

Walkthrough notes 8, 17, 30 and the preference portion of 16.

- Charge start displays only the configured clock time in the setting row's right-hand value column, respecting the user's 12/24-hour preference.
- Temperature's caption aligns with the other preference captions.
- Live Stats density and telemetry refresh use the shared animated segmented selector; persisted preference keys and polling intervals are unchanged.
- The battery-charge parameter and compact scooter-reading label include `(%)`.

Validation: the core suites pass; production and separate native-runner APKs build and verify. `DeveloperSettingsValidation` in `preferences` mode passes 290 assertions on a disposable Android API 36.1 emulator at 411×840 dp, with its screenshot inspected. The checks cover selector touch targets, selected/radio semantics, readable labels and the developer-menu gate. No physical phone or scooter was used.

Reproduce with `bash tests/run.sh`, `bash build.sh`, `bash android-tests/build.sh`, then run `com.jjricks.volt.tests.DeveloperSettingsValidation` with `mode=preferences` on an emulator. The charge-time change retains the existing scooter time decoding; this pass changes its display format, not scheduling behavior.

## Follow-up: Units submenu and spacing

Distance/speed, temperature, and clock format now live together in App preferences → Units. The existing preference keys remain unchanged. Units stays selected through unit-driven UI rebuilds and returns to App preferences with Back; the wide menu keeps App preferences highlighted. Dashboard App preference shortcuts can also enter and leave the nested page. A 16 dp gap separates the final sliding selector from the Units/Text size group.

The production and native-runner APKs build and verify. Updated `DeveloperSettingsValidation` covers all five selector groups, persistence through rebuilds, Units visibility and Back navigation, and the selector-to-submenu gap. It passes 427 assertions at 360×800 dp, 725×604 dp, and Fold cover 344×882 dp with 140% app text (1,281 assertions total). Compact and unfolded screenshots were inspected. Emulator 5592 only; no owner phone or scooter was used.
