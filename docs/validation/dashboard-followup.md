# Dashboard editing followup validation

Validated 2026-10-14 against the 0.10.0 source baseline. This change addresses
walkthrough notes 2–6, 23, 26, 37 and 38.

## Behavior

- New widgets remain drafts outside the saved dashboard and Undo history until
  their main options Save button is pressed. Changing a parameter in a child
  sheet does not commit the draft. Canceling or leaving the screen discards it.
- Shortcut reorder and deletion keep the same sheet and scroll container. Rows
  slide into position; removal uses the existing bounded, disposable poof effect.
- Empty leading grid rows are removed without collapsing intentional gaps below
  the first widget. Committing a gesture uses its current geometry, including a
  release before the next layout frame.
- Quick access can occupy one of five columns. Narrow cards show icons with full
  accessible labels and state. A 58 dp width floor protects 48 dp shortcut targets;
  narrow rails can shrink to three grid rows. Below 96 dp, Move and Options
  stay on the card; Options opens Edit/Duplicate/Remove in a compact popup.
  The configuration sheet still has only Save at its bottom. Wider rails keep
  their existing direct actions, and the resize target remains separate.
- The recorder can be one row high at 192 dp or wider. Its secondary description
  is hidden in this compact presentation, and its resize target stays separate
  from the other edit controls. Narrower recorders retain their taller minimum.
- Duplicates animate from the source card into their new position. Geometry is
  committed immediately; reduced motion skips the transition.
- Selected readings also appear at the top of the reading picker, independently
  of the search filter. Both copies share selection state; removing a pinned
  choice slides the remaining rows into place.
- Scooter values use one shared fitted font size, derived from every value's
  actual width and row height. Compact captions and padding leave more room for
  larger numbers. Overflow remains available when a large custom list cannot
  fit at its readable minimum.
- Scheduled charging shortcuts include the reported start clock after On/Off,
  using the chosen 12/24-hour preference. Cached clocks are identified in their
  accessible state. The Stats shortcut is labeled Live Stats.

## Checks performed

`bash tests/run.sh` passed all 15 suites (46,227 assertions/checks), including
leading-row normalization and one-column shortcut width coverage. The signed
app and native runner builds passed; APK signature/alignment checks passed.

The isolated native `ResponsiveDashboardValidation` runner passed these cases:

| Window (dp) | Text scale | Mode | Coverage |
| --- | --- | --- | --- |
| 540 × 960 | 100% | followup | Actual five-column 4+1 photo/shortcut split; unsaved draft cancellation, child options and Save/Undo; stable shortcut sheet identity; icon-only accessibility and target geometry; one-row recorder and its editor controls; equal scooter number sizes without unnecessary scrolling; pinned reading search/uncheck/Save; 12/24-hour schedule clocks |
| 360 × 800 | 140% | followup | Same applicable checks at larger text; compact scooter card uses the grid's scaled row height |
| 344 × 882 | 100% | cards | Existing Fold cover default dashboard and lock states |
| 725 × 604 | 100% | editor | Existing unfolded editor controls, six-column layout, options, duplicate/delete, Undo/Redo, gesture commit/cancel and animation cleanup; duplicate starts at its source frame |
| 540 × 960 | 100%, animations disabled | followup | Same editing and geometry behavior with reduced motion |

The existing quick-access suite also passed at 344 × 882 on the preceding build
before the final compact-caption/recorder-target refinements. It exercised every
shortcut label, 2–8 limits, duplicate prevention, in-memory toggle/menu actions,
and new-widget reveal behavior.

Native screenshots were inspected for the five-column edit layout, pinned
selection sheet, compact recorder, and shared scooter-number sizes.

Final validation APK SHA-256:
`b516005b5a915792e45ebea04579baf9a0a12196fc9125139ffd1719b081efc8`.

Local reproducibility evidence is under `work/next-dashboard-validation/`, with
the final cases in `native-verified/` and `native-reduced/`. The runner refuses
physical devices and existing owner app data. No real scooter actions, owner
phone installation, release publication or merge was performed for this change.

## Follow-up: three-row Quick Access rail

All narrow Quick Access rails now allow three grid rows, including the roughly 72 dp rail in a 448 dp phone's five-column layout. At less than 96 dp, Move and Options remain on the card as independent 48 dp targets; Options opens an Edit/Duplicate/Remove popup. Edit opens the existing configuration sheet with Save as its only bottom action. At 96–143 dp the direct actions occupy two rows; wider cards retain their original editor and two-row minimum. The change does not rewrite stored dimensions.

Claude caught that the first height rule only helped wider rails, missing the owner's five-column phone case. The revised native fixture includes that actual 448×960 dp layout, plus unfolded 725×604 and Fold cover 344×882 with 140% app text. It checks 58, 70, 72, 94, 96, 113, and 143 dp editor geometry with separate 48 dp targets; popup Edit, Duplicate and Remove, including Undo; Save-only configuration; and the existing draft, shortcut, recorder, selected-reading and schedule-clock behavior.

Signed production/native-runner builds verify. All three revised follow-up cases passed on disposable emulator 5592 with Bluetooth disabled. No owner phone or scooter touched. The local evidence is under `work/round2-small-ui-evidence/compact-actions/`.
