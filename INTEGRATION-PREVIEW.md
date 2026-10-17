# Integration preview: all ten open PRs on top of main

- Branch: `claude/integration-preview` (reference only: pushed for review, never to be merged; no PR)
- Base: `d3d61e8` (main, Volt 1.3.1)
- Head: `ccf9795`
- Date: 2026-10-17

Every merge uses `--no-ff` and every commit ends with the Co-Authored-By trailer. Nothing was released or deployed, and no private research material entered the repo. The merge step itself used no emulator or device; none was assigned for this step, and the task only asked for pure tests, the guard and the builds.

## Merge order (also the suggested order for merging the PRs)

| # | Branch | Branch tip | Preview merge | Conflicts |
|---|---|---|---|---|
| 1 | claude/site-home-simplify (#48) | 8ed26da | 9436d07 | none |
| 2 | claude/ride-speed-colors (#49) | 8d93330 | ce69694 | none |
| 3 | claude/widget-motion-polish (#50) | 45acbe0 | ea05f94 | tests/run.sh |
| 4 | claude/ride-auto-stop (#51) | 86e207c | 803e132 | tests/run.sh, android-tests/build.sh |
| 5 | claude/unified-backup (#52) | e74d49d | 65e06b1 | AppDataArchive.java, tests/run.sh |
| 6 | claude/compat-registry (#53) C1 | dd08887 | b6aa642 | AppDataBackup.java, tests/run.sh |
| 7 | claude/compat-admission (#54) C2 | f8e4ec0 | 3716df5 | android-tests/AndroidManifest.xml, android-tests/build.sh |
| 8 | claude/compat-discovery (#55) C3 | d0c6043 | d5ce3d7 | none |
| 9 | claude/compat-guided-tests (#56) C4 | 259d30a | 3242f0e | android-tests/build.sh |
| 10 | claude/compat-controls (#57) C5 | 5ee7e4a | 8a048d6 | android-tests/build.sh, docs/development-notes.md, BleController.java, ModelRegistry.java, tests/ModelRegistryTest.java |

How the branches stack: C2 contains C1, C3 contains C2, and C4 contains C3. C5 branches from C2 (compat-admission), alongside C3/C4. Once #53 is merged, each later compat PR adds only its own commits. #57 is where the C3/C4 and C5 work first meets.

Two ways to reproduce:

- **On a scratch branch:**
  ```
  git worktree add <dir> -b <name> d3d61e8
  git merge --no-ff claude/<branch>
  ```
  Repeat the merge for each branch in the order above and resolve each conflict as described below.
- **On main, one PR at a time:** after each PR is merged, update the next branch from main (merge or rebase) and resolve the same conflicts in the same way. Then apply the cross-branch fixes, each to the PR named in its "Belongs to" line.

## Conflicts and resolutions

### 3. widget-motion-polish (#50)

- **tests/run.sh**: an additive conflict. Both kept: the RouteSpeedScale sources and run line (#49), then the LockSliderTrail sources and run line (#50).

### 4. ride-auto-stop (#51)

- **tests/run.sh**: additive. Kept #49 and #50, then the RideAutoStopPolicy loop and RideAutoStopStoreTest (sources and run lines).
- **android-tests/build.sh**: additive. Kept both, so the runner list is `MotionPolishValidation.java \` then `RideAutoStopValidation.java`. The earlier line needs a trailing backslash added.

### 5. unified-backup (#52)

- **AppDataArchive.validatePreferenceTypes**: main's hand-written type lists (where #51 had added `ride_auto_stop` to the Boolean list) against #52, which replaced the body with `BackupRegistry.validateTypes(all)`. Took #52's version. `BackupRegistry` already registers `ride_auto_stop` as a Boolean in App preferences, and BackupRegistryTest asserts this, so #51's intent is kept.
- **tests/run.sh**: additive. Kept every earlier suite, then #52's Backup* sources and its run block (BackupRegistryTest with the source path, plus the /dev/shm loop).

### 6. compat-registry (#53, C1)

- **AppDataBackup.java**: C1 added `validDescriptorFields(JSONObject)` next to `AndroidStorage`, while #52 changed `AndroidStorage` to implement `BackupArchive.Storage`. Kept C1's method and #52's interface.
  - C1's call `if(!validDescriptorFields(p)) throw …` inside `validateProfiles` merged without a conflict.
  - In #52, `validateProfiles` still runs for every staged backup on Android through `BackupArchive.Storage.validateProfiles` → `AppDataBackup.validateProfiles`. So C1's bounds still apply on device: `pid_advertised` is 0-5 digits, `fw_*` are printable ASCII of at most 16 characters, `pid_advertised_wall` and `fw_wall` are numbers, and `name_default` is a boolean.
  - #52's pure reader (`BackupProfiles.parse`) did not apply these bounds. Cross-fix B (below) adds them.
- **tests/run.sh**: additive. Kept everything, then C1's `COMPAT_TESTS` block and its run loop.

### 7. compat-admission (#54, C2)

- **android-tests/AndroidManifest.xml**: additive. Kept the RideAutoStopValidation, CompatibilityGatingValidation and Ut5SimulationScreens instrumentation entries.
- **android-tests/build.sh**: additive. Added a trailing backslash after `RideAutoStopValidation.java`, then `CompatibilityGatingValidation.java` and `Ut5SimulationScreens.java`.

### 8. compat-discovery (#55, C3)

- Merged cleanly.

### 9. compat-guided-tests (#56, C4)

- **android-tests/build.sh**: additive. Kept every earlier line; added a backslash after the Ut5SimulationScreens line, then `GuidedTestValidation.java`.

### 10. compat-controls (#57, C5)

- **android-tests/build.sh**: additive. Added a backslash after `GuidedTestValidation.java`, then `CompatControlsValidation.java`.
- **docs/development-notes.md**: kept both sets of bullets: C3/C4's Reads, Compatibility check and Guided tests, then C5's Registry rows, Variants, UI, Allowlist, Speed family, New keys and OemControlsTest, followed by the shared CompatibilityGatingValidation and CompatControlsValidation bullets.
- **BleController.java**: kept C5's `simulationRegion()` and `setSimulationRegion(...)`, and kept the C3/C4 `setSimulationModel`, which already contains C5's `simulation.setModel(simulationModel); tickSimulation();`:
  ```java
  public void setSimulationModel(String pid){String next=pid==null?"":pid;if(simulation!=null&&!next.equals(simulationModel)){if(discovering)stopDiscovery();clearDiscovery();}simulationModel=next;if(simulation!=null){simulation.setModel(simulationModel);tickSimulation();}changed();}
  ```
  It clears the check only while simulating and only when the model changes. The rest of the file (decision/runtime with `discovery`, `test` and `oemOnly`) merged without conflicts.
- **ModelRegistry.VERSION**: C4 had 3 (guided-test table) and C5 had 5 (More-screen, speed and lighting rows). The combined registry is **6**.
  - The class comment keeps C5's version 3/4/5 history and says the guided-test table was developed as its own version 3 and that version 6 is the first registry with both.
  - Every other version check is relative (`== ModelRegistry.VERSION`, or `>= 3` in OemControlsTest), so only ModelRegistryTest needed a change.
- **ModelRegistry statics and helpers**: kept everything from both sides.
  - From C4: `TESTABLE`, `SHARED_UNITS`, `testRule`, `testable`.
  - From C5: all `OEM_*`, `SPEED_*`, `LIMIT_*`, `START_*`, zone and S lights, the prefix tables, `zoneLight`, `lightNote`, `maxSpeedTable`, `noMoreScreen`, `bluetoothLockScreen`, `chargeFamily`, `assist`, `firmware`, `gated` and `contains`.
- **tests/ModelRegistryTest.java**:
  - Took C5's expectation that `charge_limit` for 24431 is OEM-mapped and `drive_speed_limit` is still unmapped. C4's "home-screen rows only" check is now out of date.
  - The version check is now `VERSION==6`, with the message "Registry version records both the guided-test table and the More-screen, speed and lighting rows".

## Cross-branch fixes (separate commits on the preview branch)

| Commit | Fix | Belongs to |
|---|---|---|
| db3ea52 | A. Guided-test journal classified as excluded | #56 (compat-guided-tests), after #52 |
| 38d3e3d | B. C1 profile fields checked and round-tripped in unified restores | #53 (compat-registry), after #52 |
| c6c21c4 | C. PRIVACY: profiles keep the advertised model ID, which travels in backups | #53 |
| ccf9795 | D. A simulated region change clears simulated check results | #57 (compat-controls), after #55; optional |
| 56a98bc | E. Compatibility reports list group S's OEM-only light controls, and imports keep them | #57 (compat-controls), after #55 |
| ea0ff79 | F. A region change clears the simulated check only when the simulated descriptor changes (refines D) | #57 (compat-controls), after #55; fold into D |
| 6efef24 | G. The full backup journey seeds and checks non-empty C1 profile fields | #53 (compat-registry), after #52; next to B |

### A. db3ea52: Keep guided-test records out of every backup

- **Gap:** after all merges, `BackupRegistryTest` failed with: `BleController.java: storage root no_backup/compat-tests is unclassified`. C4's TestJournal writes `no_backup/compat-tests` (plus `.tmp`).
- **Fix:**
  - `BackupRegistry.ROOTS` lists both paths as excluded ("Guided-test records of this installation"), following decision D4: the records stay on the device.
  - BackupRegistryTest asserts both are excluded.
  - Docs: a new row in `docs/app-storage-inventory.md`, a row in the "Never exported or restored" table in `docs/unified-backup.md`, and "guided setting test records (kept on this phone)" added to the Never-included list in `docs/app-data-backup.md`.
- **Other new keys checked:** no other new SharedPreferences key or storage root came from C1-C5.
  - The simulator model and region are memory-only fields of BleController.
  - The compatibility check keeps no persisted settings.
  - Reports are built fresh and saved only through the existing picker flow (`pending-export-*`, already excluded).
  - The source scan passes.

### B. 38d3e3d: Check and round-trip model evidence profile fields in unified restores

- **Fix:** `BackupProfiles.parse(items, true)` now applies the same bounds as `AppDataBackup.validDescriptorFields` (`validModelEvidence`) to staged backup profiles. `BackupArchive.finish` uses it. Local profiles are still parsed without these checks, so an existing phone can never be locked out of making a backup.
- **New test:** `BackupPlanTest.modelEvidence` checks:
  - A profile with every C1 field plus an unknown newer field survives a Scooters merge into a phone with another scooter, byte for byte, and its values read back.
  - Replace everything restores `profiles.items` byte for byte.
  - Eight out-of-bounds variants are refused at staging: non-digit or numeric `pid_advertised`, a 17-character or non-ASCII or null `fw_*`, a string `pid_advertised_wall`, a boolean `fw_wall`, and a string `name_default`.
- **Check that the test bites:** with `BackupArchive` reverted to `parse(items)`, the new test fails, as it should.

### C. c6c21c4: PRIVACY

- The stored-data row for profiles now includes the advertised model ID and when it was seen, and says that backups including Scooters carry it.
- The `fw_*` fields exist in profiles, but no code fills them yet.

### D. ccf9795: Simulated region change clears check results

- C5's new `setSimulationRegion` now follows the same rule C3/C4 apply to model changes: only while simulating, and only when the value changes, it stops a running check and clears its results. The simulated region feeds the descriptor (region/SKU), so an old report would no longer match.
- This is a judgement call, not a test failure. Drop it if the owner prefers a report that keeps the region it was taken with. It never affects a real scooter.

### E. 56a98bc: Report group S's OEM-only light controls

- **Gap (independent review, low):** `CapabilityResolver.all()` built only `ScooterCommands.catalog()` plus `clock_sync`. `CompatibilityReport.knownFeature()` used the same list. As a result, C5's `ambient_light_mode`, `logo_light` and `daytime_light` (`ScooterCommands.oemOnly()`) never appeared in a report, and an import would have stripped them.
- **Fix:**
  - `all()` adds the OEM-only controls between the catalogue and `clock_sync`, but only those the model's registry maps (`support == KNOWN_ENCODING`, whatever the runtime reason).
  - The report whitelist accepts the three keys.
- **Design choice:** on a UT5 MAX the rule for these keys is `INCOMPATIBLE / MODEL_EXCLUDED` ("Not on this model"). Shared, unidentified and other OEM-group models get `UNKNOWN_MAPPING`. Listing them as not-offered would add three refused rows to every non-S report and change "Controls: N available of M" (UT5 MAX would go from 37 of 37 to 37 of 40). The filter keeps every non-S decision list, and so every non-S report, byte for byte the same. Only group S gains genuinely new rows. This matches `BleController.controlOffered`, which offers an OEM-only key only when it is `KNOWN_ENCODING`.
- **Tests:**
  - CapabilityResolverTest: group S lists the three rows in order, mapped, OEM-mapped and available with a valid status, and still listed while disconnected. Seven other descriptors (UT5 MAX serial, both and advertised; 2585; 2504; S serial-only; unknown 39991) list none, and none is `KNOWN_ENCODING`.
  - CompatibilityReportTest: the group S report carries and round-trips the rows, and the readable summary shows them. A forged import keeps `logo_light` and drops an unknown key. Six non-S reports equal a catalogue-only report exactly.
  - Check that the tests bite: reverting either half makes them fail.
- **Emulator check (emulator-5554, gating mode):**
  - Mapped (24431) report: "19 available of 40", with the three light rows available.
  - UT5 MAX report: 37 of 37. Unknown-model report: 1 of 37.
  - The UT5 MAX and unknown decision sections are identical to the earlier gating run's reports.

### F. ea0ff79: Exact region rule for the simulated check (refines D)

- **Reviewer nit:** ccf9795 cleared on any simulated region change, including on the UT5 MAX simulator. `DeviceDescriptor.simulated` ignores the region there, so the descriptor and the report do not change.
- **Fix:** `setSimulationRegion` now compares the region the simulated descriptor would carry before and after, and clears only when it differs. In practice that means any model other than the UT5 MAX.
- **New emulator assertion (CompatibilityGatingValidation, after the interrupted check):**
  - Setting the same region keeps the check.
  - A different region keeps the check on the UT5 MAX simulator and clears it on the mapped and unknown models.
  - The test then restores the region.

### Checked, no change needed

- **Ride auto-stop (#51) with compat:**
  - The policy uses the 0x91/0x92 motion pushes and arms only after fresh stationary pushes and a recoverable loss. A model that never sends them never arms, so it fails closed.
  - The compat work treats the same pushes as shared passive frames (ReadPolicy `PASSIVE`, DiscoveryCoordinator) and uses the same `stationary()` for parked-only gates, so both features agree.
  - It needs a profile serial, which every model's authenticated profile has, and it ignores the simulator.
  - Guided tests are refused while recording, so an automatic stop cannot interrupt one. `ride_auto_stop` is registered in BackupRegistry.
- **Ride speed colours (#49):** RouteSpeedScale scales from recorded scooter or GPS speed with a 10-120 km/h top and no UT5-specific limits or model gates.
- **CompatibilityReport and guided tests against backups:**
  - Reports are not stored, and the journal is now excluded (fix A).
  - The only compat data in a backup is the C1 profile fields inside `profiles.items` (Scooters category). The docs now cover it (inventory, unified-backup line 280, fix C).
- **Registry version:** CompatibilityReport, TestJournal and GuidedTest read `ModelRegistry.VERSION` at runtime, so with version 6 they record 6. No test fixture hard-codes 3 or 5.

## Test results at ccf9795

Toolchain: a local JDK 21 / Android SDK 36 environment (see docs/building.md), run from the repository root.

| Command | Result |
|---|---|
| `bash tests/run.sh` | exit 0, every suite passed |
| `python3 tests/owner-capture-guard-test.py` | 11 tests OK |
| `bash build.sh && bash android-tests/build.sh` | exit 0, `dist/navee-offline-lock.apk` and the validation APK built and verified |

Pure test counts for the suites this round touches:

| Suite | Result |
|---|---|
| BackupRegistryTest | 3835 checks |
| BackupArchiveTest | 937 checks |
| BackupPlanTest | 57 checks (includes the new model evidence test) |
| BackupTransactionTest | 273 checks |
| ModelRegistry | 377 assertions |
| CapabilityResolver | 13608 assertions |
| MutationGate | 627 assertions |
| DiscoveryCoordinator | 5450 assertions |
| CompatibilityReport | 478 assertions |
| TestJournal | 55 assertions |
| GuidedTest | 583 assertions |
| OemControls | 14539 assertions |
| RideAutoStopPolicyTest | 43 assertions |
| RideAutoStopStoreTest | 22 assertions |
| RouteSpeedScaleTest | 38 checks |
| LockSliderTrail | 13 checks |

Before the cross-fixes, the merged tree (8a048d6) failed only BackupRegistryTest, on the unclassified `no_backup/compat-tests` root.

Not run: the emulator journeys (BackupInventoryValidation, CompatControlsValidation, GuidedTestValidation and others). No emulator serial was assigned for this step. These are worth running on a disposable emulator before the last compat PR lands, especially BackupInventoryValidation after a guided test has written its journal.

## Test results after E and F (head ea0ff79)

| Command | Result |
|---|---|
| `bash tests/run.sh` | exit 0. CapabilityResolver 13644 assertions, CompatibilityReport 525, OemControls 14539 |
| `python3 tests/owner-capture-guard-test.py` | OK |
| `bash build.sh && bash android-tests/build.sh` | exit 0 |
| `run-compat-gating.py --serial emulator-5554` (gating, default cases) | 344x882:dark:1 PASS, 725x604:light:1.4 PASS |
| `run-compat-gating.py --serial emulator-5554 --mode controls` (default cases) | 344x882:dark:1 PASS, 725x604:light:1.4 PASS |

- The emulator had Bluetooth off and used synthetic data. Its wm size, density and font_scale were restored afterwards (1080x2340, 440, 1.0).
- The compatibility Controls screenshots are unchanged above the fold; the new light rows sit lower in the list.
- The new light rows are confirmed in the exported mapped report and its shared text.

### G. 6efef24: Full backup journey checks non-empty C1 profile fields

- **Gap:** `android-tests/FullBackupJourneyValidation.java` seeded only default C1 fields (`name_default=false`, empty `pid_advertised` and `fw_*`). A restore that dropped them would still have passed.
- **Fix:** the journey (complete export, `pm clear`, Replace everything) now seeds synthetic non-empty values: `pid_advertised` "24431" (5 digits), `pid_advertised_wall`, `name_default=true`, all five `fw_*` strings and `fw_wall`. After the restore it requires each value to come back exactly, through the real `ScooterProfiles` model. The file byte hashes and preference digest checks already in the journey cover the stored bytes.
- **Source:** a cleaned-up version of the emulator-5556 smoke diff. Same values, written as named constants with one assertion.
- **Results (head 6efef24):** `bash tests/run.sh` exit 0; `python3 tests/owner-capture-guard-test.py` OK; `bash build.sh && bash android-tests/build.sh` exit 0; `python3 android-tests/run-backups.py --serial emulator-5556`: all 9 journeys PASS (full-export, full-restore-check, legacy-v1-restore, adapter, dashboard-transfer, ui-344x882-dark-1, ui-725x604-light-1.4, maintenance, inventory). Output is in `<local path>`.
- The emulator had Bluetooth off and used synthetic data. Its wm size, density and font_scale were 1080x2340, 440 and 1.0 afterwards.


## Smoke tests on the combined build (disposable emulators, synthetic data, Bluetooth off)

At `ccf9795`, plus re-runs after the later cross-fixes:

- emulator-5554: `run-compat-gating.py` gating and controls modes, and `run-guided-tests.py --mode all`, at 344x882 dark/100% and 725x604 light/140%: all PASS. Exported reports contain no serial, MAC, owner key, name or route.
- emulator-5556: `run-backups.py`, all 9 journeys PASS, including complete export -> `pm clear` -> Replace everything. `run-backup-scenario.py --render` PASS. `run-ride-backups.py` 4/4 PASS. At `6efef24` the journey also seeds non-empty C1 profile fields and checks each one survives.
- emulator-5558: `run-rides.py` 3/3, `run-ride-auto-stop.py` 4/4, `run-motion-polish.py` 10/10, `run-battery-widgets.py` 5/5, PhoneRideValidation denied/granted 6/6 each: all PASS. `run-responsive.py` 31 PASS / 5 FAIL. The 5 failures are pre-existing on main `d3d61e8`: ResponsiveDashboardValidation still expects "Time to full", but main shows "Time to full (est.)".
- emulator-5560: simulated UT5 MAX screens (Home, Scooter, More, Quick access, settings search) compared with a 1.3.1 build at both sizes. The only intended difference is the new More row "Compatibility / Model check & report" (#55). Learned-range, charge capture and charge estimate runners PASS.

Independent Claude Opus 5.5 reviews of every merge (`git show --remerge-diff`) and of every cross-fix commit found no blocking defects.
