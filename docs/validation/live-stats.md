# Live Stats browser validation

PR15 combines the original Live Stats organization pass with the owner's follow-up from the integrated test build.

## Behavior

- Overview keeps the battery summary and an actionable empty Favourites state. Readings and charts have independent stars and can coexist in a single manually ordered list. Reorder exposes labelled 48dp up/down buttons; the mixed order survives navigation and process recreation. Existing reading favourites migrate in stable catalogue order.
- Readings keep the dynamic reading count, its alignment, and its 48dp row spacing. The redundant Favourites filter is removed. Search opens matching rounded, otherwise collapsed categories.
- Charts discover every numerical key retained for the active scooter, plus current numerical readings. Text-only values are excluded. Search matches label, key, and category using the same group structure as Readings. Categories start collapsed unless the rider has chosen otherwise.
- One saved time-window control applies to every chart, including favourite and expanded views: 2 minutes, 10 minutes, 1 hour, 6 hours, 24 hours, 7 days, 30 days, or All. Expanded views expose the same control and the existing measured-point trace inspection.
- All starts at the earliest retained sample before choosing a history tier. A recent two-minute dataset therefore retains detail rather than being reduced against an epoch-wide range. Older minute/daily tiers retain the existing storage policy and aggregation; the page explains that older data is summarized. Connection gaps remain explicit.
- Charts allocate their graph only after entering the viewport. Collapsed, filtered, and offscreen graphs do not query. Visible graphs refresh from live values and query history at most every five seconds, with one in-flight query per card, at most 600 saved points plus a bounded live tail. Historical-key discovery runs at most once a minute. Results are guarded against window/source changes, closed screens, and removed cards.
- Display conversions respect distance/speed and temperature preferences. Saved or disconnected readings stay muted and timestamped. Simulator data is labelled and uses only the sample source, without history writes or real-history queries.
- Live Stats has a larger pulse dot closer to the heading. The pulse stops when hidden/unfocused and respects reduced motion.
- The original 10-second away reset, wide independent Readings pane, disclosure/accessibility labels, and narrow-value stacking remain.

## Validation

`bash tests/run.sh`: all 16 suites pass (46,228 checks), including mixed favourite ordering migration, duplicate/unknown IDs, persistence, add/remove, and boundary moves.

`bash build.sh` and `bash android-tests/build.sh`: signed app and separate runner build and verify. Existing Java8/deprecation/native-access warnings only.

`HistoryValidation`: six isolated SQLite fixture groups pass. New checks cover recorded numerical-key discovery, exclusion of text/cache-only values, profile isolation, All preserving recent detail and reconnect gaps, All including the oldest retained tier, and shared-window bounds. Existing retention, pressure pruning, storage cap/journal, and clear tests also pass.

`LiveStatsValidation` refuses physical devices, existing private data, missing Bluetooth permissions, and enabled Bluetooth. It creates a synthetic profile and sample data; no real scooter command, ride, or charge capture is started. Tests cover the reading-count geometry, collapsed groups, search, numerical catalogue completeness, lazy graph creation, fullscreen sample data/trace inspection, chart stars, mixed overview, saved manual ordering, and retention/expiry of sections.

All five native UI cases passed (930 assertions total):

| Viewport (dp) | Theme | System font | Motion |
| --- | --- | --- | --- |
| 344 × 882 | Dark | 100% | Enabled |
| 725 × 604 | Dark | 100% | Enabled |
| 360 × 800 | Light | 140% | Enabled |
| 650 × 850 | Light | 130% | Enabled |
| 344 × 882 | Dark | 100% | Disabled |

The final saved/live-tail merge also has a dedicated regression: returning to a chart must retain intermediate persisted samples and reconnect gaps. It was rerun in narrow and unfolded large-text cases.

The runner scrolls matching charts into view before asserting lazy graph creation. The removed-filter check is scoped to Readings so the separate wide Overview heading is not mistaken for a filter.

Reproduce on a **fresh disposable emulator** with matching app and runner signatures:

```sh
python3 android-tests/run-live-stats.py \
  --adb /path/to/sdk/platform-tools/adb \
  --serial emulator-5594 \
  --app /path/to/stats.apk \
  --runner /path/to/parked-validation.apk \
  --out /path/to/evidence
adb -s emulator-5594 shell am instrument -w \
  local.navee.offlinelock.tests/local.navee.offlinelock.tests.HistoryValidation
```

The first command replaces app data on the explicitly selected disposable emulator. This pass does not install on the owner's Pixel. Actual scooter firmware availability still determines which numerical parameters appear.
