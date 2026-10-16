# Home and concise UI closeout

This pass integrates the new Home widgets, charging estimates and UI copy cleanup.
It was reviewed independently by GPT-6 Astra Ultra and GPT-6.1 Sol Ultra after
implementation, with focused cross-review of the resulting fixes.

## Coverage

- Review the approved Home, Scooter, Rides, More and setup changes against the
  supplied design reference. Preserve deliberately skipped items, capture consent,
  stale-reading indications, uncertainty labels and opt-in online basemaps.
- Verify narrow-card lock geometry, nonoverlapping editor targets, whole reading
  values and stable normal/Edit content sizes. Preserve existing layouts and slots.
- Exercise the real dashboard import activity-result dispatcher independently of
  Shizuku. Cancelling the confirmation must leave the layout export unchanged.
- Restore default widgets, recreate the Activity and undo the reset.
- Cancel encrypted-backup export through the document-picker result and verify
  Close becomes usable again without leaving sensitive temporary data behind.
- Cancel charge capture and change profile while its dialog is open; neither may
  change the wrong profile's charger preference. Check stock-reference source tags,
  unknown charge cutoff and low-current displays.
- Inspect inline and expanded ride graphs and maps, preserve basemap consent and
  full-detail spikes, and bound hatch drawing when a long ride is heavily zoomed.
- Exercise combined system/app large-text layouts on the unfolded window.

## Test limits

Native tests use disposable Android emulators, synthetic profiles/readings and
Bluetooth disabled. The tested windows include a 344 dp cover-screen layout and
725 dp unfolded layout, with normal and enlarged fonts. They do not establish
physical Fold behavior, real Bluetooth range/recovery, charger identification or
out-of-sample charging-time accuracy. The stock finishing model remains experimental;
see [charging estimate validation](charge-finishing-estimates.md).

Private design exports, screenshots and raw owner recordings are not required by
or bundled with the app. Reproducible synthetic native fixtures live in
`android-tests/`; deterministic logic tests live in `tests/`.
