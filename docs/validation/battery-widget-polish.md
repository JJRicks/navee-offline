# Battery widget presentation

Walkthrough notes 13, 20, 22, 24, 25, 27, 28, 29, 39 and the dashboard selector portion of 16. This branch builds on the dashboard editing follow-up; charge-session learning is a separate change.

- Charging mode expands/collapses above the dashboard with a short, interruptible height/fade transition. Disabling animations applies the final state immediately; detach/close settles and cancels the animator.
- Charging power and time use separate responsive metric tiles. Charging displays watts; raw current remains available in Live Stats. The battery overview continues switching between riding and charging readings, using green for charge input and red for power draw.
- Reading-list widgets use labeled icon tiles. Long diagnostic values shrink to a readable floor, then wrap rather than clipping at a fixed height. Capacity experiments emphasize estimated Wh, the advertised comparison and a comparison bar, with evidence and methodology available through Estimate details. Experimental/proxy/sample labels remain explicit.
- Battery health has font padding to preserve its large percentage glyphs.
- Live trend, history and health widgets persist a Relative/Absolute axis option through saved slots and duplication. Time-window choices use the shared sliding selector. Chart bounds and adaptive timestamps are documented in `widget-chart-axes.md`.
- Shared selectors have fading edges to expose horizontal overflow. Icon-only quick-access rails dim their content during editing so the toolbar remains legible.

Validation:

- 53,625 core checks passed, including 7,398 chart-scale checks; signed app and separate native-runner builds verified.
- `BatteryWidgetValidation` passed 319 assertions across 344×882 dp, 725×604 dp, 360×800 dp with 140% text/light theme, and compact reduced-motion cases. Screenshots inspected. Checks include interrupted charging transitions, color/state changes, readable values, padded health glyphs, reachable capacity evidence saved chart-axis preferences, and long diagnostic-value wrapping.
- `DashboardSlotsValidation` passed 361 assertions. Its legacy-layout expectation now includes the requested removal of leading empty rows when deriving a new column count, while verifying original saved 1–4-column rectangles stay unchanged. Axis options are covered by full round trips and malformed-snapshot rejection.
- The chart layer's separate native matrix passed on compact/unfolded/large-text screens, including very narrow chart widths, raw touch values and preserved data gaps.

Reproduce after building both APKs with `android-tests/run-battery-widgets.py --adb <sdk-adb> --serial emulator-<port> --app <app-apk> --runner <validation-apk> --out <evidence-dir>`. It refuses physical devices and replaces only the explicitly selected disposable emulator's app data. Local evidence is in `work/next-battery-validation/native-fit/`.

No physical scooter, phone installation, merge or release was used for validation.
