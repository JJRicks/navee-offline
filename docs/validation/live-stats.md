# Live Stats refresh validation

Scope: walkthrough notes 7, 14, 16 (Stats section navigation), 18 and 19.

- Overview has a larger Favourites heading and a dashed, actionable empty state. The empty area opens Readings (or scrolls its existing wide pane to the top).
- The Favourites filter has a filter glyph, a clear selected outline, and a layout that stacks when its text and count need more space.
- Reading categories use one rounded group each, with larger headings and disclosure arrows. Unset expansion preferences default to collapsed; explicit prior choices remain saved. Search and the favourites filter open matching groups.
- Readings share available width between labels and values. Values stack below the label before their words are split by a narrow value column.
- The shared sliding selector replaces Stats' individual section pills. Compact padding keeps all default-size labels visible; larger text retains the existing horizontal-scroll fallback.
- A return within 10 seconds retains the selected section. Longer tab or app absences reset to Overview and clear its saved scroll. Monotonic time and boot count prevent a reboot from extending the grace period.
- The page and navigation label are Live Stats. Its heading is grey while disconnected. The connected pulse is decorative, stops when hidden/unfocused and respects Android's animation setting.
- Power, voltage and scooter speed are three independent chart cards, each using its own measured trend and unit. Speed respects the display units. Stale charts remain grey and gaps stay intact.

## Validation

`bash tests/run.sh`: all 15 suites passed (46,219 assertions/checks).
`bash build.sh` and `bash android-tests/build.sh`: signed app and isolated runner built successfully. Only the existing Java 8/deprecation/native-access warnings were emitted.

The new `LiveStatsValidation` runner refuses physical devices, existing private app data, missing Bluetooth permissions and enabled Bluetooth. It uses a synthetic scooter profile and the existing sample-data mode, and verifies that no actual Bluetooth transport or recording starts.

All five final native cases passed. Screenshots were inspected in compact and unfolded layouts, including light-theme large text and the large-font stacked-value regression.

The native matrix covers:

| Viewport (dp) | Theme | System font | Motion |
| --- | --- | --- | --- |
| 344 × 882 | Dark | 100% | Enabled |
| 725 × 604 | Dark | 100% | Enabled |
| 360 × 800 | Light | 140% | Enabled |
| 650 × 850 | Light | 130% | Enabled |
| 344 × 882 | Dark | 100% | Disabled |

Checks include the empty affordance; default collapsed groups; heading, filter, and disclosure readability; search and favourites; distinct chart instances/data/units; default section-label fit; tab and app-background retention/expiry; and live/disconnected heading state. Screenshots cover Overview, collapsed/filtered Readings, and both ends of the chart list.

Reproduce on a **fresh disposable emulator**, using the app/runner built with the same local key:

```sh
python3 android-tests/run-live-stats.py \
  --adb /path/to/sdk/platform-tools/adb \
  --serial emulator-5588 \
  --app /path/to/stats.apk \
  --runner /path/to/parked-validation.apk \
  --out /path/to/evidence
```

This command intentionally replaces app data on the explicitly selected emulator. It never selects a physical phone. No phone installation or live scooter command is part of this validation.
