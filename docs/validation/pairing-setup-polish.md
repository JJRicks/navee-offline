# Pairing setup polish

## Browser helper

The pairing page uses a single centered step card and compact progress indicator.
Connection troubleshooting, capture details, computer export and advanced recovery
instructions use native expandable sections. Broad USB access, temporary logging
consent, private-key handling and the regular-browser/recovery-data reminder stay
visible where they affect the user's decision. USB and capture still require
separate unchecked checkboxes and explicit buttons.

Capture, protocol, transport and cleanup modules are unchanged. The only runtime
JavaScript edits are button labels and the final import instructions, which now
match **Import your owner file → Choose owner file** in Volt. The build still emits
the exact script hash for CSP/SRI and denies network uploads, workers and frames.

Validation on local synthetic inputs, without USB hardware:

- All 56 browser-helper Node tests pass, including capture, cleanup, recovery,
  transport cancellation and the production UI handlers.
- Production bundle and security headers build successfully.
- Chrome checks at widths 320, 344, 390, 768, 1000 and 1280 px pass for every
  setup panel and the recovery guide. Closed and expanded help have no horizontal
  overflow; secondary export/recovery actions remain keyboard-accessible.
- Bundled fonts, text contrast, visible keyboard focus and reduced-motion checks
  pass. USB consent and cancellation, blocked script/upload attempts, blocked
  service-worker environments, and preserved legacy-origin recovery also pass.
- Desktop and narrow-screen screenshots inspected for connect, capture, working,
  export and recovery views. No new external assets, scripts or fonts.

Chrome/USB is the maintainer-selected recommended default, not a claim of new
physical-device coverage. Existing hardware-validation limits remain in the
browser helper README and setup guide. Local screenshots/tests do not verify a
deployed server or a successful physical scooter capture.

## Android

New setup flows select Chrome + USB and show its **Recommended** card first.
Explicitly chosen routes in unfinished setup flows are retained, including legacy
ADB progress that had not saved its implicit route. Full backup
restore remains prominent on the welcome screen.

Backup/restore places the ScrollView inside a container padded by system/keyboard
insets, so the scrollable viewport really ends above the keyboard. Focus and
viewport changes reveal the full password field with a small margin. Password
storage, encryption and screenshot protection are unchanged.

The native validation runner checks fresh onboarding, adding a scooter, retained
route choices, and new/repeated/restore password fields with the real emulator
keyboard. See the backup UI validation notes in `docs/development-notes.md` for
the guarded runner and synthetic-fixture requirements.

Native results and the real-keyboard regression comparison are recorded in
[Setup choice and backup password visibility](setup-app-polish.md).

## Review follow-up

The save-panel instructions and successful-save notice now match the exact Android
button **Choose owner file**. The regular-browser/recovery-data reminder appears
once, in the visible permission card. Collapsed connection help briefly notes the
browser route’s incomplete capture validation and links to the desktop helper,
with cleanup required before switching. Chrome/USB remains Recommended, and its
chooser subtitle is **Computer · a few minutes** rather than repeating its title.

These are copy-only changes. Browser-helper tests, the production build, six-width
browser checks and an Android build were repeated; the earlier real-keyboard and
onboarding behavior checks remain applicable. No new capture or device validation
is claimed by this follow-up.
