# Widget chart scales and history labels

Validated October 14, 2026, on the chart implementation branch based on dashboard commit `0eeba03`.
This is the reusable chart layer; dashboard preference persistence and the settings control are validated separately in the combined battery/chart change.

## Behavior

- Existing `set(points, unit, color, empty)` callers keep relative scaling. Optional `setAxis(metric, absolute)` persists across subsequent data refreshes.
- Absolute watts and current use a symmetric range around zero. Voltage, speed, distance, and other known nonnegative quantities start at zero. Temperature retains negative values while including zero.
- Percentage axes stay within 0–100 in both modes; relative mode still zooms into the measured interval. Bounds affect presentation only, not the stored/inspected reading.
- History charts fit up to six localized time labels using actual measured text widths. Very narrow charts with enlarged text retain one readable endpoint when two cannot fit.
- Existing explicit gaps, missing/nonfinite sample gaps, and touch inspection remain intact.

## Checks

- All 16 core suites passed: 53,625 assertions/checks, including 7,398 ChartScale checks for percentage boundaries, signed/grounded axes, extreme finite values, unchanged relative noise floors, and timestamp collisions.
- App and native runner built and signed successfully.
- Native `chart` fixture passed at 344×882dp / 100%, 725×604dp / 100%, and 360×800dp / 140% app text.
- Each native run also measured 168dp, 300dp, and 620dp charts. It verified time-label bounds, extra labels on wider plots, physical percentage bounds, symmetric power, zero-grounded voltage, refresh persistence, retained raw values during touch inspection, and gaps.
- Final screenshots were inspected. The disposable chart gallery replaces the production screen wrapper; its headings are fixture labels, not production app layout.
- Emulator only: explicit `emulator-5586`, Bluetooth disabled, synthetic data, no physical phone access or scooter commands.

Local evidence: `work/next-chart-axis-validation/native-final/`, core/build/runner logs adjacent to that directory.
The first run's screenshots were obscured by the emulator's unrelated Digital Wellbeing ANR. After stopping that emulator-only process, the final run passed with unobstructed charts. A too-strict initial fixture expected two timestamps even when enlarged text physically could not fit; it was corrected to test the intentional single-endpoint fallback.

Validated APK SHA-256:
`57d76d4ede3316d271aece7877561a2afb135b1d8fa17c8fad3f8e098eefd726`

No release, deployment, owner-phone sideload, or merge was performed.
