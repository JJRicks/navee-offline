# Second-round integration validation

The local-only validation branch combines the previously reviewed eight-PR set with these follow-up heads:

| PR | Head |
| --- | --- |
| 15 — Live Stats chart catalog | `97709cbf70533583b1187c895c33240a0e1ffdc3` |
| 16 — Units preferences | `880d3c016ce47d160d0411209ec62a722fe11470` |
| 17 — three-row narrow rails | `fa75581ca3b507b288dffcc0e1d036ea96d378ac` |
| 18 — raw ride chart inspection | `a4fed90a58cf1534a7ab40cc8bbff9a215d7188f` |
| 20 — background capture and JSON export | `808ffe1d05f1097344103416235a7430ffc11b75` |

Tested product commit: `dd34ca52f8a2530101121d0d4d1b22528fc4eaac`. This branch was not pushed, merged to main, released, or installed on an owner phone.

## Integration resolutions

- `ScooterService.startRide` retains the ride-analysis branch's positive-is-draw setting before starting a new journal, and the already-recording guard, while adding the capture branch's `RECORDING_STARTED` service reevaluation.
- The service change callback still updates charge capture, observes telemetry, follows the configured range mode, and then notifies listeners. The renewed recording wake-lock policy is preserved.
- The pure suite list combines all existing integrated suites with the new Stats favourite ordering suite. The UI merges retained the earlier narrow-editor dimming and shared selector improvements.

## Validation

- Signed production and native-runner APKs build and verify.
- All 20 combined pure suites passed: 53,818 checks. These ran immediately before the final UI-only PR17 popup correction; that correction does not change any pure-tested class behavior beyond the Android-backed widget height rule.
- Live Stats native fixture: 187 assertions each at cover 344×882 dp dark/default font and unfolded 725×604 dp light/140% system font (374 total).
- Ride-analysis fixture: 84 checks each at 344×882 and 725×604 dp, including inline/fullscreen raw inspection (168 total).
- Dashboard follow-up fixture passed at the actual five-column 448×960 dp phone layout, including narrow three-row rails, separate editor targets, popup actions, Save-only options and Undo.
- Charge-capture fixture: 75 assertions at 344×882 dp, including background lifetime policy and raw JSON export. Real Bluetooth capture remains untested.
- Preferences fixture: 427 assertions; Units navigation, selectors, and spacing pass in the combined app.
- Phone ride fixture: all six groups pass both without and with GPS permission, including recording through screen-off beyond the idle timeout, capture cleanup, and export. These synthetic fixtures deliberately bypass the Bluetooth-authenticated production Start button.
- Isolated SQLite history fixture: all six groups pass, including numeric chart catalog/profile separation and retained-history query windows.

Compact Stats and the actual five-column phone dashboard screenshots were inspected. Logs, screenshots and exact APK hashes are saved in local `work/round2-integration-evidence/`; `identity.json` pins the tested product commit. All native work explicitly targeted disposable emulator 5592, with Bluetooth disabled and no owner identity. Existing emulator 5554 and owner phones were untouched.

Physical scooter/charger background behavior, OEM battery-manager policy, and long real rides/charges still require owner testing. Independent PR review status is tracked on GitHub; this document records compatibility tests, not a self-review or approval.

## Stats picker review follow-up

Integrated PR15 `c87af30132c14dc0d506fb3d6c50c3d832be6ad5` at tested product commit `36ecabccd0763357371685cb9fee942d14607775`. The merge is confined to Stats and its fixture: the compact Window picker exposes all eight choices, fullscreen content respects window insets, and reorder rows share the app typography. Shared segmented controls were not modified.

The combined signed app and runner build and verify. Focused native Stats validation passes 780 assertions each at 344×882 dp dark/default font, 448×960 dp dark/140% system font, and 725×604 dp light/140% system font (2,340 total). The compact and large-text fullscreen window-choice screenshots were inspected: every option is visible and selectable. Earlier unchanged-domain evidence remains valid. Logs, screenshots, product identity and preserved APKs are in `work/round2-integration-evidence/stats-picker-final/`.
