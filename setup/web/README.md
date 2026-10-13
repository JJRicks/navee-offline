# Volt USB setup helper

A static, browser-local owner-capture wizard for `https://jjricks.com/pairscooter/`.
That is the intended hosting address; this PR supplies the source and deployment
instructions. Publication is a separate operation and is not verified by a build.
Chrome or Edge on a computer talks directly to the selected Android phone over
WebUSB. No Python, installed ADB executable, account login endpoint, upload API,
analytics, CDN scripts, or server-side capture processing is involved. Some
Windows phones still need an Android USB driver.

The owner must already have activated/bound the scooter, signed into the official
NAVEE app, and be able to connect to the nearby scooter. This helper obtains the
same six-byte Bluetooth identity used by `setup/capture-owner.py`; it does not
recover NAVEE passwords, bypass ownership, or replace initial OEM activation.
Future OEM versions may stop emitting the required message. In that case capture
fails; it never guesses an identity.

## Build and local use

Node 20+ and npm are needed **only to build the website**. Dependency versions are
pinned; `package-lock.json` fixes their full transitive tree and integrity hashes.

```sh
cd setup/web
npm ci --ignore-scripts
npm test
npm run build
python3 -m http.server 8080 --bind 127.0.0.1 --directory dist
```

Open `http://localhost:8080/` in desktop Chrome or Edge. Localhost is a secure
context for WebUSB. Remote hosting requires HTTPS. Do not open `index.html` as a
`file:` URL. The simple local server supports functional development; production
must additionally send the response headers emitted in `dist/headers.json`.

The optional `test/browser-check.mjs` uses an already-installed Playwright and
Chrome; it never downloads a browser or connects to hardware. Supply absolute
`PLAYWRIGHT_MODULE`, `CHROME_PATH`, and `QA_DIR` environment variables. It checks
320/390/768/1280 px layouts, all panels, initial consent gating and cancellation,
the local recovery guide, CSP upload/script rejection, and service-worker blocking.

## Consent and privacy boundaries

1. Before opening Chrome's USB picker, the user acknowledges that ADB grants
   **broad developer access**, beyond this helper's specific capture operation.
   Android separately prompts to authorize the temporary ADB key. The page asks
   users to leave “Always allow from this computer” unchecked.
2. A separate checkbox and Start action authorize temporary NAVEE logging,
   force-stopping/restarting NAVEE, bounded capture, and automatic cleanup.
   No NAVEE mutation happens merely by choosing a phone.
3. This first version supports only the main personal Android user (user 0).
   Secondary users and work-profile NAVEE installs are rejected before logging
   changes. Because the log property is device-wide, capture also refuses when
   NAVEE is installed in any other user/profile; profile IDs are checked locally
   and names are discarded. Unknown/unreadable enumeration fails closed.
   All NAVEE start/force-stop commands explicitly target user 0. The launcher is
   resolved to a validated component in the fixed NAVEE package before starting.
   The page
   identifies that user's exact installed package UID. Logcat is filtered **on the phone** by UID, NAVEE tag, authentication
   marker, and fresh phone epoch. Other apps cannot inject an accepted message by
   merely reusing the tag. Unsupported UID filtering fails closed. Each response
   is bounded to 1 MiB, and polling stops after 90 seconds.
4. The 17-byte auth frame has its header, opcode fields, supported role, reserved
   byte, trailer, length, and checksum verified. Conflicting identities/roles are
   rejected. Arbitrary errors and raw phone logs are never printed or displayed.
5. Export is unavailable until NAVEE has been force-stopped, its **exact** original
   log property read back, and the session lock released. A capture is discarded
   on cancellation, disconnect, failed cleanup, or ambiguous result.
6. Saving to phone Downloads and downloading a computer backup are explicit,
   separate buttons. The phone path is generated internally, includes a random
   suffix, and is opened with shell noclobber; an existing file is never replaced.
   The identity travels over shell stdin rather than a shell command argument.
   The helper never imports or replaces a Volt profile. The existing native file
   picker is the final owner-approved import step.

The ADB RSA key and owner identity stay in page memory. No IndexedDB key store,
clipboard write, URL parameter, console message, or telemetry contains the owner
identity. The only persistent browser record contains the selected phone serial,
random capture token, capture stage and original log property. It is a recovery
journal, not an owner file. It is removed after verified cleanup. An explicit
Download recovery instructions button can save these non-credential details and
carefully quoted macOS/Linux recovery commands to a local text file.

Downloads files are sensitive. Android Downloads is shared storage and computer
downloads inherit browser/OS permissions; a website cannot promise mode 0600.
Users should remove the phone copy after import or keep backups somewhere private.
The official NAVEE process may itself emit broader verbose logs temporarily; this
helper reads only its filtered authentication messages, and does not clear the
phone's global log buffer.

Finish disconnects USB, attempts `USBDevice.forget()`, and clears the temporary key
and captured result. Revoking Chrome's USB permission **does not revoke Android's
trusted debugging keys**. The UI separately explains turning off USB debugging
and revoking Android authorizations if the user checked “Always allow.”

## Cleanup and interruption recovery

The browser and optional Shizuku helper share an exclusive shell-owned lock:

```text
/data/local/tmp/volt-owner-capture.lock/
  owner       webadb (Shizuku uses shizuku)
  token       random 64-character lowercase hex session ID
  original    exact pre-capture log property (bounded printable ASCII)
  started     phone epoch
```

Browser capture additionally writes `guard.sh`, `ready`, `stopping`, `status`,
`guard_done`, and a transient `cleaning` gate. No owner identity is stored here.
The original setting is read only after exclusive acquisition, so a second helper
cannot record the first helper's temporary VERBOSE value as its original.
Journal contents are always treated as data; recovery does not source them or
execute a script read from the phone.

Before enabling logging, the browser writes the recovery record, starts a
detached `nohup` watchdog, and requires its readiness acknowledgment. The phone
timer attempts cleanup after 120 seconds even if the browser vanishes. Start and
restoration share a gate, and a stopping marker blocks late starts. The signal
handler waits for a bounded in-flight child and exits rather than resuming later
mutations. A watchdog exiting after a restoration failure leaves an acknowledgment
so an explicit later recovery can restore and release the ended timer.

Cleanup always force-stops NAVEE before restoring the exact previous property
because NAVEE caches logging state in its process. Normal cleanup also waits for
the watchdog's sole terminal acknowledgment before retiring the completed lock
with an atomic rename and deleting only this session's known files. Interrupted
deletion can leave a small retired metadata directory, but cannot leave the global
reservation without the token/original needed for recovery. Unknown files block
retirement and are preserved.
A stale token cannot operate on another helper's lock. If the final release reply
was lost after lock removal, recovery reacquires the lock exclusively and repeats
force-stop and restoration; absence alone is never proof of success.

No browser can guarantee background code survives a device reboot, OS kill, an
ADB daemon failure, or a broken transport. Unconfirmed cleanup keeps export blocked
and the journal intact. The bundled `recovery.html` is public/account-free and
explains recovery and the manual fallback. Missing/partial journals, unknown lock
owners, or a forcibly killed timer require verification; never blindly delete a
lock or replace an unknown original setting with an assumed empty value.

## Hosting requirements

Deploy **only `dist/`**, atomically, at `/pairscooter/`. All links/assets are relative
and support this subpath. Do not expose node_modules, source maps, repository
metadata, or runtime captures. There is no upload route to deploy.

Apply every header in `dist/headers.json` to all responses in this location.
`script-src` allows **only the exact hash of the bundled script**, paired with its
HTML `integrity` value. Do not relax it to `'self'`: same-origin Cloudflare injected
scripts or other site apps must not run in this sensitive page. `connect-src 'none'`
blocks fetch/WebSocket/beacon uploads, `worker-src 'none'` blocks new workers, and
HTTP `frame-ancestors 'none'` plus `X-Frame-Options: DENY` prevents framing. WebUSB
needs `Permissions-Policy: usb=(self)` in this location. No-store avoids stale HTML
and hash mismatches across deployments. Do not add CSP reporting endpoints that
could accidentally become a side channel.

Disable HTML rewriting, email obfuscation, analytics insertion, Rocket Loader and
challenge-script injection for this location at the reverse proxy/CDN. A restrictive
CSP may correctly block those injected scripts; fix the hosting rule instead of
loosening the policy. Verify origin and CDN responses after publication. HTTP CSP
and the early HTML meta policy should agree; `frame-ancestors` must be HTTP-only.

The chosen subpath shares `jjricks.com`'s origin. WebUSB permission is origin-wide,
and CSP cannot protect against a malicious server replacing both the HTML policy
and script. The page refuses to capture if controlled by an existing service worker
and cancels if a controller appears. It does not unregister workers belonging to
other apps. A dedicated origin would provide stronger isolation if desired later.
Use a clean browser profile if a root-scoped service worker controls this page.

## Validation limits

Automated parser/state-machine tests cover strict frames, stale/malformed/conflicting
rows, output bounds, unsupported UID identity, consent-independent export gating,
timeouts/cancellation, each failure stage, unverified cleanup, damaged recovery
records and wrong phones. Real POSIX-shell tests with fake Android commands exercise
exact restoration, independent watchdog cleanup, concurrent-helper refusal,
start/timeout races, a signal during a bounded child, stale tokens and failed cleanup.

These tests do not by themselves prove a real WebUSB handshake, a real NAVEE owner
capture, or all Samsung/Pixel firmware behavior. The app and page must still be
tested with user-approved hardware before claiming end-to-end compatibility.

Primary API references: [Tango USB](https://tangoadb.dev/tango/daemon/usb/),
[Tango custom credential stores](https://tangoadb.dev/tango/daemon/credential-store/),
[Chrome WebUSB](https://developer.chrome.com/docs/capabilities/usb).
