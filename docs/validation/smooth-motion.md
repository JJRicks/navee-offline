# Motion and load-in validation

This pass follows the owner's 0.11.0 Pixel feedback. Several interactions flashed or snapped: the red preference selectors popped something up for a frame, the ride map arrived a few frames late, and tabs and sub-menus hard-cut. Favourites reordering also needed a grab-handle drag. The goal was clean, consistent, intuitive motion with nothing excessive.

## Behavior

- **One vocabulary.** `Motion.java` holds the curves (standard ease-out, an even fade and a shared-axis travel curve) and the durations. Nothing exceeds 280 ms, and every animation becomes an instant change when Android animations are off.
- **Moving between pages is a fade-through.** This covers tabs, More sub-menus (App preferences, My scooters, PIN & proximity and the rest), Stats sections, Scooter → Lights and onboarding steps.
  - The old page clears during roughly the first 100 ms as it starts to leave. The new page then fades in while finishing a short glide (at most 48 dp), so the two never show through each other.
  - Deeper levels and later tabs arrive from the right; Back and earlier tabs arrive from the left.
  - The tab bar stays put, and its selection dot glides to the new tab.
- **Different content in the same place fades through without travelling.** For example, picking another ride in the wide detail pane.
- **The same content refined crossfades in place.** This covers theme, units, temperature and Live Stats detail rebuilds, and the full chart data replacing its preview. The crossfade starts from an opaque snapshot of the old window, so the rebuild underneath is never visible.
- **Windows.**
  - Sheets rise from the bottom and full-screen pages slide in.
  - Dialogs fade in and up, like Text size.
  - Every dialog and sheet is configured before `show()`. Several used to restyle after `show()`, drawing a frame or two at the wrong size or position; the widget-settings sheet first drew one empty, centred frame.
- **Scrollbar flash.** Android flashes the scrollbar of every scroller that attaches to a visible window. After a settings rebuild this was the "pop for a frame". Rebuilt pages and dialog content now keep their bars hidden until the user scrolls.
- **Scroll restores.** Stats, More and Rides restored scroll positions with `post()`, which drew the top of the page for a frame and then jumped. Restores now apply in the first frame. A Stats section change keeps the section tabs where they were instead of jumping to the top.
- **Load-in.**
  - **Ride map:** the route map crossfades over its "Loading route…" placeholder while the content below glides down. The chart's own controls update in place.
  - **Tiles and thumbnails:** basemap tiles fade in as they arrive, and list thumbnails draw their route in over the placeholder icon.
  - **No-phone-file rides:** rides without phone files no longer reserve map space that then collapses.
  - **Small additions:** late Compare lines, Stats and chart group expand/collapse, and the recording card all glide.
- **Favourites.**
  - Reorder opens a bottom sheet with a grab handle on each row. The held row lifts and follows the finger, neighbours glide aside with a light tick as the slot changes, and the row settles on release. The order saves immediately.
  - Screen readers get one element per row with Move up / Move down actions and a position announcement.
  - The Reorder control is a 48 dp icon button with a tooltip and a spoken label.

## Validation

- `bash tests/run.sh`: all 20 suites pass.
- `bash build.sh` and `bash android-tests/build.sh`: both the app and the separate runner build and verify.

Emulator fixtures ran on a read-only `Medium_Phone_API_36.1` AVD with Bluetooth off, using default cases unless noted:

| Fixture | Cases | Result |
| --- | --- | --- |
| `run-responsive.py` | 20 layout cases, 320–852 dp, app text 1.0–1.4×, system font 1.0–1.3× | All pass |
| `run-rides.py` | 344 × 882 dark, 725 × 604 dark, 360 × 800 light at 1.4× | All pass, 123 checks each |
| `run-live-stats.py` | 344 dark, 725 dark, 360 light 1.4×, 650 light 1.3×, 344 dark with animations off | All pass, 788 assertions each |
| `run-battery-widgets.py` | 4 cases (including animations off) plus saved slots | All pass |
| `run-charge-capture.py` | 344 dark, 725 light 1.4× | Both pass |
| `LaunchValidation` | Permissions denied; granted | Both pass |
| `DeveloperSettingsValidation` | Default; `preferences` with animations off | Both pass |
| `PhoneRideValidation` | `granted` | Pass |

One 725 dp `run-live-stats.py` run in the long sweep hit the script's 180 s instrumentation timeout. Rerun alone it passed five times (37–76 s; the unmodified 0.11.0 build takes 73 s), and the final five-case run passed. The new large-text drag check also found a real bug: a taller, wrapped row could not pass a shorter one. Neighbours now give way when the held row's leading edge passes their middle.

`LiveStatsValidation` now exercises the new sheet:
- dragging the chart handle above the reading saves the mixed order;
- the dropped row settles with no leftover translation, scale or elevation;
- the row is named for screen readers;
- the Move down and Move up actions save, and the last row offers no Move down;
- the order survives screen recreation.

The 725 dp `RidesAnalysisValidation` case caught a crash during development. Dismissing an expanded chart within its crossfade detached the chart while its parent was still detaching children; removing the snapshot at that moment corrupted that pass. Cleanup now waits one main-loop turn, and the case passes.

Frame-by-frame screen recordings (60 fps, plus 4–5× animation scale for the slower parts) covered:
- the four tab directions;
- More sub-menus and Back;
- the Live Stats detail selector, with no frame showing a scrollbar or layout pop;
- Stats sections forward and back;
- the favourites drag;
- phone ride details, including the map placeholder, map and tiles;
- ride switching in the 725 dp wide layout;
- the Add widget sheet;
- dashboard edit mode.

Not recorded: the onboarding step change and the Scooter → Lights shortcut. Both use the same `Motion.page` path as the More sub-menus. Software rendering on the emulator drops frames during heavy redraws, so final smoothness still needs the owner's Pixel.
