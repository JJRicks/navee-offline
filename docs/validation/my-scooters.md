# Multi-scooter setup and management validation

## Behavior

**More → My scooters** lists saved scooters directly. Add opens guided onboarding; Connect selects the tapped scooter and ends the old transport; the selected entry offers Cancel while connecting and Disconnect when ready. Rename and confirmed Forget are bound to the tapped profile ID. Forget retains saved ride and charge files. Profile-changing actions recheck recordings, pending saving, backup/restore and scooter commands when invoked, including at confirmation time. Adding or switching to a real scooter exits simulation.

An owner account identity is not a unique scooter identifier. Identifier-less owner-file restores ask for an explicit same-account destination or Add another scooter. Fresh helper setup cannot absorb an older unconnected scooter. Repeat helper captures may reuse the current onboarding attempt's profile; hardware matches must not contradict known identifiers. A staged request has a deterministic new-profile ID and durably bound destination so replay cannot silently add duplicates or switch its target. Onboarding progress is committed before the recovery file is removed.

A failed first connection without a verified saved Bluetooth address ends in DISCONNECTED/Try again. Saved scooters retain their existing bounded retry/backoff and explicit-cancel/background behavior. No new authentication method or speculative N65i protocol change is included.

## Checks run

- Full `bash tests/run.sh`, including 1,030 BackupArchive assertions for complete/category/legacy restores and same-account destination rules.
- App and separate instrumentation APK builds with a local synthetic test signing identity.
- `android-tests/run-backups.py`: all 12 journeys pass, including full export/reset/restore, legacy restore, owner-only merge, existing and new same-account destinations, adapter/dashboard/UI/maintenance/inventory.
- `FirstConnectionValidation`: 24 assertions for unknown-address failure, missing profiles, identity rejection, saved retry and explicit/background cancellation. The production failure transition is exercised with Bluetooth disabled.
- `MyScootersValidation`: 87 assertions each at 344 × 882 dp dark/default and 725 × 604 dp light/140% app text. Covers profile selection, targeted rename, recording/saving/command guards, confirmation race, saved-file preservation, simulation exit, Add, Cancel and Back. Menu screenshots inspected after navigation animations settle.
- `CompatibilityGatingValidation -e mode owner-profiles`: 72 assertions through the real staged-import method. Covers same-account separation, current-flow repeat import, hardware reuse/conflicts, account/role mismatch, typed-profile reuse, four interrupted-import boundaries and conflicting recovery targets.
- Read-only implementation QA found the simulation and recovery edges above; fixes were rechecked before handing the new head to Claude.

All native checks used synthetic profiles on a disposable emulator with Bluetooth disabled. They do not prove the N65i's physical authentication succeeds. A controlled retry with other scooters powered off, followed by a connection-diagnostics export if needed, remains the hardware follow-up. No owner identity or real scooter diagnostics were published with these tests.
