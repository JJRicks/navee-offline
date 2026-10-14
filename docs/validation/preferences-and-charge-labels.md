# Preferences and charging labels

Walkthrough notes 8, 17, 30 and the preference portion of 16.

- Charge start displays only the configured clock time in the setting row's right-hand value column, respecting the user's 12/24-hour preference.
- Temperature's caption aligns with the other preference captions.
- Live Stats density and telemetry refresh use the shared animated segmented selector; persisted preference keys and polling intervals are unchanged.
- The battery-charge parameter and compact scooter-reading label include `(%)`.

Validation: the core suites pass; production and separate native-runner APKs build and verify. `DeveloperSettingsValidation` in `preferences` mode passes 290 assertions on a disposable Android API 36.1 emulator at 411×840 dp, with its screenshot inspected. The checks cover selector touch targets, selected/radio semantics, readable labels and the developer-menu gate. No physical phone or scooter was used.

Reproduce with `bash tests/run.sh`, `bash build.sh`, `bash android-tests/build.sh`, then run `local.navee.offlinelock.tests.DeveloperSettingsValidation` with `mode=preferences` on an emulator. The charge-time change retains the existing scooter time decoding; this pass changes its display format, not scheduling behavior.
