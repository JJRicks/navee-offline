# Local next-version integration validation — 2026-10-14

This is compatibility validation, not an independent review or merge approval. The integration branch is local only; no owner phone, remote merge, release, or deployment was used.

## Latest checkpoint

Final tested integrated product head: `db84c644a231f095958be176a193620a96520ce8`. All requested feature and review-fix heads are present through PR #21 `4d72a52513eee795938cea7282ab433168520676`. The final combined app/native runner build, narrow 140% preference/light selectors (852 assertions), and compact Live Stats matrix passed. The last core run at `0664473d530a4e73d38816193812140bd287f46c` passed all 19 suites (53,785 checks) and charge lifecycle (49 assertions); the later selector-only change touches none of those pure core classes or charge behavior. Earlier UI matrices below cover unchanged screens and the targeted map/battery/dashboard changes. There are no unresolved source merge conflicts. The legacy full keyboard fixture limitation below remains explicit.

Final APK: `../next-integration-evidence/selector-followup.apk`, SHA-256 `0cf07e79893e0b1bbb5d7c826c250f109f963278a4c53c1b79a11596571356a3`. `../next-integration-evidence/final-identity.json` records that product head and build identity.

## Source and build (initial combined checkpoint)

- Branch: `codex/next-version-validation` in `work/next-integration-validation`.
- Baseline: `5cb5e46c521494a689c258770b264c3ad917f233`.
- Tested integrated product head: `561e3f9052395b4a04c62cafeeb1791a91c57633`.
- Feature heads: range `374da66`, stats `120c208`, preferences `abc0dba`, dashboard `0eeba03`, rides `18f1c1b`, battery/charts `83f5fd79632d725d803c8d47e42856a7bed18803`, charge capture `b2a1bfdbbbac68cfdd8581a8542313389a74dcb9`, selectors `bb54199534889342f749d9c26ce172354b457ab0`.
- Production APK build, signing verification, and alignment: passed.
- APK: `../next-integration-evidence/final.apk` (relative to the worktree).
- SHA-256: `c0db11e2351b188846a845fe4977439115050c513c1d10ebad5d08677faeff73`.
- Native runner: `../next-integration-evidence/final-build/parked-validation/parked-validation.apk`.
- All Android actions explicitly targeted emulator `emulator-5586`. Bluetooth was disabled. Existing emulator 5554 and physical devices were untouched.

## Merge resolutions

Test registrations and core suite lists were combined. `ScooterService.changed()` retains charge observation, telemetry observation, and range follow in sequence. `MainActivity.ChoiceSheet.refresh()` retains the range follow-mode display and confirmed-state selector behavior; the range algorithm remains in its existing radio choice flow. No unresolved conflicts remain.

The dashboard draft test now selects Battery voltage and asserts that the click occurred. The previous Battery charge label was renamed by the preferences change, and its optional loop could silently skip the intended sub-option action. This is a strengthened test-only correction.

## Results

| Validation | Result |
| --- | --- |
| Core suites | All 19 passed, 53,751 checks; run after charge integration. Later changes affect UI/text fit/selectors only. |
| Dashboard | Passed 540×960 follow-up, 344×882 cards, 725×604 editor cases. Draft cancel/save, top normalization, narrow shortcuts, one-row recorder, fitted values, pinned selections, scheduled time labels, editor targets, undo/redo, and six-column fit covered. |
| Live Stats | Passed 344×882 and 725×604, dark, 100% text. |
| Rides | Passed 344×882 and 725×604, dark, 100% text; 33 checks each. |
| Battery widgets | Passed 344×882 dark/100% (85 assertions) and 360×800 light/140% (75 assertions), motion enabled. |
| Saved dashboard slots | Passed 361 assertions across 13 workflows. |
| Navigation and organization | Passed all 11 workflows, including all five destinations, grouped settings/search, More timeout, map preferences, offline route interaction, and large/light text. |
| Preferences/developer/selectors | Passed 814 assertions, including confirmed-state pending/rejected/unknown choices, seven-tap developer gate, simulation isolation, and background teardown. |
| Charge session capture | Passed 49 assertions on the combined service callback. An earlier separate integrated charge run also passed. No real Bluetooth charging capture claimed. |
| Focused keyboard comparison | Baseline and final integrated app both passed three native filter-dismiss/immediate-refocus cycles. |

Representative dashboard, Stats, and battery screenshots were inspected. The large-text battery fixture intentionally creates a tall card and remains scrollable; its lower stats extending below the viewport are not clipped from the card.

## Search fixture limitation

The existing full `SearchFocusValidation` is not green on the baseline: it failed at the outside-navigation dismissal assertion. The integrated full run reached a swipe assertion and failed there. That swipe starts at the scroller's bottom using view coordinates and does not account for the floating navigation overlay or IME surface pan.

A diagnostic fixture using native coordinates then observed one later observer-driven focus dismissal: native tap (540, 631) lay inside field bounds (47, 563–1033, 700), focus was acquired after ACTION_UP, then `SearchFocus.Binding.observeKeyboard` cleared it while the IME was hidden. This one sequence was not independently reproduced in focused comparisons, so it is not classified as a newly introduced product bug.

`SearchFocus.java` is byte-for-byte unchanged from the baseline. The matched focused fixture ran three cycles of opening battery search, natively tapping Favourites, then immediately refocusing. Both baseline and integrated versions passed. The sole adaptation for the integrated fixture is the navigation accessibility label Stats → Live Stats; assertions and timing are identical.

No product change or speculative fix PR was made. Diagnostic SearchFocus edits were removed from the integration worktree and preserved only as an evidence patch. The full fixture remains an explicit validation limitation, not a passing result.

## Evidence and remaining review work

Evidence root: `../next-integration-evidence/`.

- `final-matrix/results.json`, per-suite logs, instrumentation results and screenshots.
- `final-navigation/results.json`, individual runner output and screenshots.
- `baseline-search/`, `navigation/SearchFocusValidation.txt`, and `search-fixed/` preserve full-fixture failures.
- `baseline-focused/` and `integrated-focused-current/` preserve passing focused results.
- `integrated-focused-fixture.patch` records the one-line navigation-label adaptation.
- `search-native-coordinates-diagnostic.patch` preserves the reverted diagnostic edit.
- Adjacent `../next-integration-core.log`, `../next-integration-final-build.log`, and `../next-integration-final-runner.log` preserve build/core output.

## Review follow-up checkpoint

Integrated product head `8eccd5a629da52dba9119316a41702c8d42ff302` adds:

- Dashboard test-only correction `d65ca2718f7864d566b24e59d62b32e44c32098f`, pushed to PR #17 and handed back once for independent review.
- Ride review fixes `485294dcc66984112f7fbec0d91d1ce5c0fcc790`, including light-map route contrast, full zoom-control bounds, and bounded visible chart drawing.
- Battery reading fit `a3d92b92abedadb0414bca9634e0b1c90d3ae6ae`, keeping short values and units on one line before allowing long diagnostics to wrap at the readable font floor.

The combined app and native runner rebuilt successfully. All 19 core suites passed again, now 53,760 checks. Targeted combined native validation passed:

- Dashboard follow-up at 540×960, exercising the corrected draft-choice assertion.
- Rides at 344×882 dark/100%, 725×604 dark/100%, and 360×800 light/140%; 50 checks each.
- Battery widgets at 344×882 dark/100%; 87 assertions including the short reading, plus 361 saved-slot assertions.

The large/light grade route and compact one-line voltage screenshots were inspected. Evidence is in `../next-integration-evidence/review-followup-matrix/`. APK `../next-integration-evidence/review-followup.apk` SHA-256: `9d5931f2e73a2df5fa621393b70b25e5724583b7ec89734d6fed5f793eec3ed0`.

## Final charging follow-up

PR #20 `acb30fda633c54f60f73b0f1903071fc16050c5f` merged cleanly, including the MoreScreen preference wording alongside the other preference changes. This adds a same-state-of-charge compatibility gate between observed power and learned charger curves, honest fallback status, and qualification-aware bounded retention; the capture lifecycle is unchanged.

The final app and runner rebuilt at `0664473d530a4e73d38816193812140bd287f46c`. All 19 core suites passed (53,785 checks), including the additional charger mismatch/retention cases. The integrated `ChargeCaptureValidation` passed 49 assertions, with `real_bluetooth_capture_tested=false` as intended. No real scooter charging claim is made.

Evidence: `../next-integration-evidence/charge-followup/`, `../next-integration-charge-followup-core.log`, `../next-integration-charge-followup-build.log`, and `../next-integration-charge-followup-runner.log`. No broad UI matrix was repeated for this isolated calculation/retention change. Independent PR reviews remain separate from these local results.

## Final light-pattern selector follow-up

PR #21 `4d72a52513eee795938cea7282ab433168520676` merged cleanly into product head `db84c644a231f095958be176a193620a96520ce8`. The opt-in wrapping API is used only for the two light-pattern tracks. The original constructor, ordinary horizontal tracks, compact padding, and existing fading-edge settings remain intact.

After rebuilding the combined app and native runner, full DeveloperSettingsValidation passed at 344×882 and 140% text (852 assertions). The compact Live Stats matrix at 344×882/default text passed, including the shared default selector and its section label fit. The enlarged pattern screenshot was inspected: choices and their full labels fit across the wrapped rows.

This last run used explicitly borrowed disposable emulator `emulator-5590` after the owning agent released it. Emulator 5586 had already been stopped. Both were stopped after their respective final checks; unrelated emulator 5554 and owner phones were untouched.

Evidence: `../next-integration-evidence/selector-followup/`, `../next-integration-evidence/selector-followup-stats/`, `../next-integration-selector-followup-build.log`, and `../next-integration-selector-followup-runner.log`. Integration remains local and does not substitute for independent review, remote merging, or release authorization.
