# Changelog

## 0.9.0 — 2026-10-13

### Dashboard and setup

- Home's Layout sheet has three named save slots per scooter. Each keeps the widget list and options, column choice, and folded/unfolded arrangements together. Restore defaults and current edits preserve the saved slots; replacing or restoring a slot asks for confirmation. Restoring a slot also restores the phone's shared column choice without replacing other scooters' widgets.
- **First run, unfolded:** on foldables' inner screens and other wide screens, the numbered setup steps show a step list beside the current step: the scooter, how long setup takes and each step marked done, current, to do or not needed. On a book-posture hinge the split falls on the fold. Welcome, the path choice and the finish screen stay single-column.
- Optional **Use Shizuku on this phone** owner capture on Android 11+, using shell-backed Shizuku 13+. Debugging permission, capture consent and importing the result are separate steps. The captured identity stays in memory until import.
- A **Chrome/USB testing preview** at [pairscooter.jjricks.com](https://pairscooter.jjricks.com/) guides computer users through USB authorization, a short capture, and saving their private setup. The dedicated origin keeps its USB permission and recovery state separate from the rest of the website. Capture processing stays in the browser; there is no account sign-in or capture upload service.
- Both new helpers restore logging and stop NAVEE before making a captured result available. Bounded commands, a shared session lock and interruption recovery protect cleanup. The browser forgets USB permission after safely completed sessions where the browser supports it. Existing computer capture, backup import and manual identity entry remain available.

### Upgrade and limits

Install over the preceding official APK to keep profiles, dashboards, saved slots and recordings. Initial capture still requires Android debugging access and an already signed-in, owner-bound NAVEE app. Everyday Bluetooth control remains local.

The browser helper remains a testing preview in this app release. A successful real-scooter capture through either new helper is still awaiting validation; the available phone checks were performed with the scooter out of range. Browser capture supports the main personal Android user only. See VERIFICATION.txt and the setup documentation for test scope and recovery limitations. Existing experimental battery and phone-sensor estimates retain their limitations.

## 0.8.0 — 2026-10-13

A full visual redesign of every screen. Bluetooth, commands, storage, exports and preference keys are unchanged.

### Design

- New "Volt" look: near-black screens, soft rounded cards, Saira type (bundled, SIL Open Font License) and a single scooter-red accent. Red marks things you can change or act on; grey cards are information; last-seen values stay grey with their times. A light variant follows the Appearance setting.
- Floating tab bar with Scooter as the raised centre button. Tabs are now Home, Stats, Scooter, Rides, More.
- Dialogs use rounded Volt surfaces with red buttons; pickers open as bottom sheets.

### Screens

- **First run:** a step-by-step onboarding replaces the first-time setup guide's wall of text: choose capture, backup file or 12-character identity; a checklist; USB debugging; running the capture helper (with Copy); import; Nearby devices; connecting; done. The tab bar stays hidden until it is finished. **More › My scooters › Run setup again** reopens it.
- **Home:** one header with the connection line, scooter name and edit pencil. The lock is a full-width bar: tap anywhere on it or slide it (locked: knob on the right; unlocked: red knob on the left). Ride recording is its own card. The battery overview is a tall red cell; while charging, a charging card with the scooter photo, a progress line to the charge limit, time remaining and battery-side power appears above the widgets. All widgets, sheets and edit controls are restyled.
- **Scooter:** one scrolling page grouped into Ride, Battery, Lights, Dashboard & sound, Security and Profile, with search. Top speed is a red card with the scooter's four choices shown in your units (the scooter still receives km/h). Choices open a sheet that waits for the scooter's confirmation. Line and star lights have their own page.
- **Stats:** red section tabs, battery overview with favourites, readings with tappable favourite stars, charts and diagnostics.
- **Rides:** record card, route thumbnails in history, a live recording card with Notes and End, full-page ride details, route map and comparison, and bottom-sheet sensor and map-source pickers.
- **More:** profile card and grouped menu, restyled submenus, and a two-step handlebar PIN entry (enter, then repeat).

### Layout and upgrade

- Expanded screens use two-column Scooter settings and separate menu/detail panes in Stats, Rides and More. More preserves both scroll positions during quick returns and resets them after one minute away from the tab or app.
- Chart choices wrap as whole labels, and diagnostic actions stack when needed. Narrow battery-health cards keep their percentage visible, and the raised Scooter tab has one accessible target.
- Setup correctly opens the scooter chooser after scanning has begun, and a newly typed owner identity replaces any abandoned pending import and name. Quick access commands remain disabled while editing the dashboard.
- Install over the previous official APK to retain profiles, layouts and rides. Initial owner capture still requires ADB; battery and phone-sensor estimates remain experimental. See VERIFICATION.txt for validation scope.

## 0.7.2 — 2026-10-12

- More keeps its submenu for one minute away from the tab or app, then returns to the settings index. Active use, quick returns, rebuilds and explicit Security shortcuts retain their expected behavior.
- USGS Topo is the default optional U.S. basemap. Choose USGS, OpenStreetMap or a custom provider directly in a ride preview or Phone sensors settings.
- Route maps support smooth pinch-to-zoom and two-finger panning, anchored under your fingers. Zoom buttons and Fit remain available.
- Map errors stay out of the imagery: policy-block images are rejected, denied providers pause requests, and local routes remain visible. Old unverified tile caches refresh before reuse. USGS close-ups enlarge available tiles instead of requesting unsupported levels.

## 0.7.1 — 2026-10-12

- Cached readings now say “Last seen.” Dates outside today use a compact uppercase day/month before the time, such as `05 MAR · 13:05`, retaining the selected system/12/24-hour clock.
- Compact connection status and dashboard timestamps shrink to stay on one line when space is tight.

## 0.7.0 — 2026-10-12

This release brings the phone recording and customizable dashboard work together after the 0.4.1 release.

### Added

- A customizable Home with 15 widget types, drag and resize previews, per-scooter layouts, and separate folded/unfolded defaults. Existing custom dashboards remain intact.
- Last-known readings and settings, visibly stale after disconnect, plus connected telemetry history with a 50 MiB storage budget.
- Optional GPS, barometer and experimental motion journals for explicit ride recordings. Saved rides include local routes, estimated grade, acceleration views, and CSV/JSON/GPX exports.
- Optional OpenStreetMap tiles and a replaceable HTTPS map provider. Bluetooth control and recording continue to work without map services.
- Battery and charging widgets with reported health, live power, and a rough remaining charging time. The capacity experiment compares qualified recorded energy with percentage drop and advertised capacity.
- Scooter naming, a UT5 MAX image, concise connection status, system/12/24-hour clocks, and a two-minute live chart window.

### Improved

- Grouped scooter settings with all lighting controls together, clearer icons, less repeated text, consistent units, and search fields that release keyboard focus.
- Responsive cards, accessible edit controls, smooth layout previews, and matching card dimensions inside and outside edit mode.
- Correct Tail light naming for the verified command; automatic headlight behavior remains firmware-controlled.
- Reopening a widget search no longer inherits the previous keyboard session and immediately loses focus; normal Back and outside-tap dismissal are retained.
- Diagnostic exports and map requests identify the installed application version instead of a stale hardcoded version.

### Upgrade and limits

Install the signed release APK over the existing official build. The package and signing identity are unchanged; no uninstall or data reset is needed. Builds signed with a contributor's local key require that same key for in-place updates.

This is an independent app for an already activated, owner-bound UT5 MAX. Initial owner setup still uses the documented ADB capture/import flow. No owner credentials are included in the release.

Battery capacity, charging time, hill grade and pocket motion are estimates. Reported battery health is not a measured capacity test. Outdoor sensor accuracy and the newest features on Samsung still need field validation. The observed Boost toggles did not reveal a reliable active-state flag in the captured status packets; automatic Boost detection is not included. See VERIFICATION.txt for the exact validation scope.

## 0.4.1 — 2026-10-12

Initial repository release with offline Bluetooth controls, owner setup, telemetry, ride recording, portable builds, and the confirmed Samsung startup fix.
