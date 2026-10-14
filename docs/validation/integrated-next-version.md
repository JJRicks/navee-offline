# Local next-version integration validation — 2026-10-14

This is compatibility validation, not an independent review or merge approval. The integration branch is local only; no owner phone, remote merge, release, or deployment was used.

## Source and build

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

PR #20's later review requested a compatibility gate between observed charging power and learned charger curves. That follow-up is not in this checkpoint and needs its final head integrated, then core and charge-capture tests rerun. Independent PR reviews remain separate from these local results.
