# Combined compatibility, backup and charging closeout

Prepared October 17, 2026 for a final independent Claude review. This is a review branch;
it does not change the release version, repository visibility or deployed applications.
Shipment is held for both the combined review and the outstanding review of #58.

## Integration boundary

Base: Volt 1.3.1, `d3d61e835752517f35af64d3a5f1c6b1a5a87252`.
The branch preserves these original PR commits as ancestors:

| PR | Included head | Feature |
|---|---|---|
| #48 | `8ed26da69cbfede62ae9c9b708129e75e18e345b` | Concise homepage and alternating lock demo |
| #49 | `8d93330cc7e64302754bdf01a3eec6f351bcccf0` | Speed colours on recorded ride routes |
| #50 | `45acbe097ea87f7dc496aa6517ede1154de5e85d` | Battery clipping, charging glow, lock motion |
| #51 | `86e207ceb0eb0358875da043e87a5c18f37dec9c` | End forgotten parked rides after link loss |
| #52 | `e74d49df617980a94f396f19370d1bd15de1a560` | Unified category-based backup and restore |
| #53 | `dd0888791c85a3a005ee90a036c5ffa069a9a2ed` | Model registry and capability decisions |
| #54 | `f8e4ec06f5b97904bd9aed1741fe3a35e7366ef0` | Shared control admission and Identify |
| #55 | `d0c6043b47ee7df0e1eaa1ac8ddcfa58d70d16f1` | Read-only discovery and sanitized reports |
| #56 | `259d30adc9527b1defbfce591e8871bd6644927b` | Reversible, journalled guided tests |
| #57 | `5ee7e4a2c99251ed0805882bc14d150a00fb8a11` | Additional OEM-mapped controls |
| #58 | `f5b9bb1008fc2b7c5dd6a33ea41a43da6e1fdd66` | Automatic charger selection and fast reference |
| #59 | `e2bc8a01d32fa02e32db8341271e6f4890fb3fda` | Original handoff documentation |

The #48–57 integration tree was checked against the reference integration preview, then the
seven documented cross-branch fixes were carried over: `db3ea52`, `38d3e3d`, `c6c21c4`,
`ccf9795`, `56a98bc`, `ea0ff79`, `6efef24`. The preview-only instruction file is excluded.
These retain model evidence through backups, exclude transient test journals, reconcile
registry version 6 and simulator resets, and include OEM-only light evidence in reports.

The combined pre-fix baseline is `e57a48ab8a7c5285b33c191fa73a7675ee7feec3`.
Reviewing the diff after that commit isolates the closeout changes from the original feature work.
The #58 merge keeps both ride auto-stop and charger detection service callbacks, and applies
the model-registry UT5 MAX gate to both charger detection and reference-curve selection.

## Findings addressed

| Source | Change and regression coverage |
|---|---|
| #49 P2 | Measure actual legend endpoint/midpoint labels. Use another row when needed; dual units and enlarged text no longer collide. Native geometry checks span 240–700 dp. |
| #50 optional P3 | Stop the charging glow when the hero is fully clipped by the viewport. Scroll/layout listeners restart it on re-entry and are removed on detach. Native checks cover partial/full clipping, offscreen refresh and reduced motion. |
| #51 P2 | Discard stationary evidence across stale motion intervals. Preserve a genuine parked run during complete radio silence until the link watchdog reports loss. One fresh zero after an unknown interval cannot revive an old run. A nonrecoverable loss cancels pending auto-stop. |
| #51 P2 | Snapshot cumulative speed coverage at each complete ride point. A trimmed parked tail uses the same endpoint for both coverage and duration; later partial readings cannot inflate learned-range eligibility. Full-detail and streaming paths share the journal calculation. |
| #52 P2 | Include profiles in destination resolution when Scooters is selected, even without recordings. Honour explicit skipping before identity matching. |
| #52 P2 | Let a complete backup repair unreadable destination profile metadata after explicit Replace everything. Snapshot raw values for rollback; partial merges still require valid local identities. Test corrupt-data rollback, process death, mapping and skipped profiles. |
| #52 P2 | Use a nonempty command array in the test launcher so Bash 3.2 with `set -u` works without `/dev/shm`. |
| #55 P2 | Start discovery attempts only at transport transmission. Tag queued frames by their discovery owner and remove only that work on stop/expiry. A request cut off before its response window completes is interrupted, not evidence of unsupported firmware. |
| #56 P2 | Track status reads across the entire connection. A new guided test or explicit restore inherits older requests, drains them, then requests a fresh confirmation. Reordered old replies cannot substitute for that probe. Invalid confirmation replies require another fresh probe. |
| #59 P3s | Qualify the serial override after excluded-product/transport checks, limit UI parity claims to compared screens, and retain post-review commit exceptions. |
| Handoff nits | Update the old charge-caption assertion and restore the compatibility runner's original emulator display/font/demo settings. |

The owner's final website direction also retains the simpler page while restoring the repository
link and “Nothing leaves your phone” headline with the optional-basemap/sharing clarification.
The page describes Volt for NAVEE scooters instead of positioning it as UT5 MAX-only; controls
remain model-dependent, and this does not claim every model has been tested on hardware.

Status replies have no request IDs. The guided-test barrier deliberately fails closed if an
earlier reply never arrives: mutation timeout leaves restoration outstanding; a precheck may
need cancellation and reconnection. It does not infer confirmation from an unrequested frame.
This is conservative response ordering, not a stronger wire protocol or a hardware guarantee.

## Validation

- Full pure-Java suite on macOS's Bash 3.2: **58 suites, 155,491 checks/assertions passed**.
  Includes archive repair/rollback, hostile archives, model admission, discovery timing,
  reordered guided replies, charger selection and charge-reference replay.
- Owner-capture guards: **11 passed**. Website build: **21 files**, JavaScript syntax passed.
  Final copy rendered in isolated Chrome at 390 px and 1440 px: no horizontal overflow or
  script errors, repository links present, privacy qualifier visible. Hero, privacy, FAQ and
  regenerated social preview inspected.
- Android app and instrumentation runner built and verified using a disposable local signer.
- Ride analysis: **413 checks per case**, 344×882 dp dark/default system text and 725×604 dp
  light/1.4× system text, both with 1.4× app text. Captured map legends inspected.
- Backup: **nine existing native journeys plus four new form cases passed**, including
  export → clear → full restore, legacy imports, damaged profiles and profile-only mappings.
  New forms inspected at folded/unfolded sizes and dark/light themes.
- Guided Bluetooth tests: **967 assertions plus 40 interruption/recovery assertions per size**,
  at 344×882 dp dark/default and 725×604 dp light/1.4× text. Includes delayed transport,
  reordered replies, explicit restoration and process death. The focused transport-queue run
  passed separately.
- Motion: two folded battery pixel cases (dark/light), normal/reduced-motion slider cases,
  and corrected normal/reduced-motion glow lifecycle cases passed. This is a targeted subset
  of the upstream motion matrix, not a claim that every original viewport was rerun.
- Responsive dashboard: **four cases passed**: 344×882 narrow, 360×800 cards with 1.4× app text,
  725×604 all widgets with 1.4× app text, and 344×882 closeout. Battery corner montages,
  slider rest/motion, glow frames, enlarged dashboards and the small editor were inspected.
- Compatibility admission: **2,050 assertions per size passed** at folded dark/default and
  unfolded light/1.4× text. OEM controls: **1,055 assertions per size passed**, unchanged after
  rebooting the disposable emulator to clear a System UI ANR that covered the confirmation sheet.
- Ride auto-stop: **four cases, 66 checks each passed**. Charge capture: **209 assertions per
  size passed**. Charge estimate controls: **226 assertions per size passed**, at the same
  folded/unfolded sizes. These recording matrices used the final app APK hash recorded below.

Final test app SHA-256: `f5536ffae3e57eb5751630a1d73085224641d9e7cba532fac9cc839415e3c8c4`.
This is a disposable-signed test build, not a release artifact.

Native checks use disposable Android 16 emulators, synthetic data and fake Bluetooth transports.
No test runner touched a physical phone or scooter. Initial cold-boot System UI/Digital Wellbeing
ANRs interrupted some runs; those artifacts were retained and the cases rerun successfully
without weakening app assertions. The fake-GATT fixture initializes Android 16's boxed busy
flag before use; that fixture correction does not change production transport code.

Backup journeys ran while the last unrelated guided-read change was being finalized. Their
backup sources match the final branch, but the installed APK hash was not captured; they are
not claimed as exact-final-APK runs. The final Bluetooth matrix covers the rebuilt transport.
The glow fixture also needed enough content to scroll fully offscreen and a wait for Android's
scrollbar fade before strict frame comparisons; pixel thresholds were not relaxed.

The historical handoff records earlier site-browser checks and original feature reviews.
Those are separate evidence, not additional tests performed during this closeout. Build logs,
screenshots, raw inputs and local signing material remain outside tracked source.

## Remaining release gates and limits

- Claude must review the final combined head and #58 before shipping. No self-approval.
- Fast-charge calibration still comes from one session. Its reported 0.407-minute replay MAE
  is a fit to that session, **not an independent accuracy result**. Another fast charge is useful
  as a holdout; below 16% is modeled. Rated capacity remains the reference.
- Real Samsung rendering, actual parked auto-stop, real guided restore and other scooter
  models still need hardware checks. Emulator checks do not establish physical compatibility.
- Preserve the current private-repository and manufacturer-image caveats. This branch does
  not resolve the public-launch image permission or make private release downloads public.
- Release metadata, production signing, final deployment and sideload belong to the later
  authorized shipping step, after the review hold is cleared.
