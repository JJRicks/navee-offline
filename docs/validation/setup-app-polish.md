# Setup choice and backup password visibility

Validated on October 23, 2026 using a disposable Android API 36.1 emulator with Bluetooth disabled. No physical phone, scooter or real owner credentials were used.

## Behavior

- A new setup attempt selects **Use Chrome and a USB cable** first, labeled **Recommended**. This applies to first launch and **My scooters → Add a scooter**. The badge sits above the title to keep the title readable at narrow widths and larger text sizes.
- Continuing saves that default route. A deliberately selected alternative survives rebuilding the screen. An unfinished default ADB setup from an older version is preserved even when that version had not saved its path key.
- The backup screen applies system/keyboard insets to an outer container so its ScrollView has the actual visible viewport height. Focus and viewport changes reveal the whole password editor with a small margin. New, repeated and restore passwords use the same behavior.
- Backup consent, encryption, password clearing, screenshot protection, disabled state saving, maintenance and restore approval are unchanged.

## Checks

The native app and separate instrumentation APK compile, sign and verify. After integrating main at `a9d8675` (PR #88), both APKs were rebuilt and the wide/light `BackupUiValidation` passed all 75 assertions again. The Python runner parses, and `git diff --check` passes.

| Fixture | Window and text | Result |
| --- | --- | --- |
| `BackupUiValidation` | 344 × 882 dp, dark, 100% system text | 75 assertions passed, plus real-keyboard checks |
| `BackupUiValidation` | 725 × 604 dp, light, 140% system text | 75 assertions passed, plus real-keyboard checks |
| `MyScootersValidation` | 725 × 604 dp, light, 140% system text | 114 assertions passed |
| `FullBackupJourneyValidation`, export | Synthetic complete backup | 30 assertions passed |
| `FullBackupJourneyValidation`, restore-check | 344 × 882 dp, 100% system text | 71 assertions passed, including restore password typing and reopening |
| `FullBackupJourneyValidation`, restore-check | 725 × 604 dp, 140% system text | 71 assertions passed, including restore password typing and reopening |

The keyboard helper taps the visible editor, waits for Android window/IME animation bounds to settle, types a synthetic password one character at a time, and requires the complete editor to remain visible above the keyboard. Creation checks switch from the new password to the repeat field with the keyboard still open and reopen it afterward. Restore checks use the real file-picker result and password flow before verifying the restored inventory and data. Password values never appear in diagnostics.

For a regression comparison, the same final fixture ran against a build with only `BackupActivity` restored to its previous implementation. It failed with **Focused password is obscured by the keyboard**. The updated layout passes that same check.

Compact and wide chooser screenshots were inspected: the recommendation, selected radio state and Continue action are readable; remaining choices remain scrollable.

These checks validate Android UI behavior and the existing synthetic backup journeys. They do not add real-scooter browser/Shizuku capture evidence or physical-keyboard/OEM-keyboard coverage.

## Chooser locator revalidation

After the recommendation subtitle changed, `MyScootersValidation` still searched
for its old full accessibility description. The route-based locator now checks
the visible title and Recommended badge separately and retains the checked-radio
assertion. Final app and runner sources were rebuilt and their hashes recorded.

- `run-backups.py`: all 12 journeys pass, including a full export/restore after
  clearing app storage and the two real-keyboard UI cases (75 assertions each).
- `MyScootersValidation`: 114 assertions pass at 344 × 882 dp dark/default text
  and 725 × 604 dp light/140% app text.
- An initial run was interrupted by an Android System UI “isn’t responding”
  dialog in the disposable emulator. The dialog/failure logs were retained;
  the complete rerun passed after dismissing it, without changing backup code.

Only the assigned disposable emulator was used and stopped afterward. No physical
phone, scooter or other running emulator was touched.
