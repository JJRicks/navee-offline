# Public release readiness

This checklist prepares Volt for a public repository and a 1.0 release. It does
not itself change repository visibility, publish an APK, or deploy the website.

Volt is an independent community app, **not affiliated with, sponsored by, or
endorsed by NAVEE or its app vendor**. NAVEE names and marks identify compatible
hardware; no trademark rights are granted by this project.

Since 1.5.0 the Android app ID is `com.jjricks.volt` and the map User-Agent is
`Volt/<version>`. Installations of the earlier `local.navee.offlinelock` app move
their data with Back up & restore ([how](getting-started.md#moving-from-volt-14x-to-150)).
NAVEE names remain only where they describe compatible hardware, NAVEE's own app,
or legacy file formats that must keep importing (`navee-offline-owner-v1`,
`navee-offline-diagnostics-v1`). The repository is `JJRicks/volt` (formerly
`navee-offline`; GitHub redirects old links).

## Preparation audit — 14 October 2026

A targeted scan covered 176 reachable commits, 971 unique file blobs and 107
refs, including 31 pull-request heads. GitHub checks covered the PR discussions
and their retained edits, 12 releases with 34 uploaded assets, and 219 retained
Actions log archives. No actual owner credentials, private signing keys, recorded
routes or operational server credentials were detected. All 1,521 files in the
uploaded source archives matched audited Git blobs. This is a bounded review,
not a guarantee against unknown or encoded secrets.

Claude session/design links were removed from the current descriptions of PRs
#5 and #7. Their older revisions and session trailers in 26 historical commit
messages remain by maintainer choice. Public work artifacts are acceptable to
the maintainer; the privacy concern is personal information and credentials,
not the presence of artifact links. The scan does not establish the contents or
access controls of external destinations. No history was rewritten during this
preparation.

The current checkout uses generic setup-file examples and broader ignore rules
for owner exports, route files and local databases. The repository remains
private, and neither a 1.0 release nor a website deployment is part of this prep.

## Before making the repository public

- Review the complete reachable Git history, branches and tags, not just the
  current files. Check release assets, issue/PR attachments, CI logs/artifacts,
  screenshots, and deployment examples too.
- Remove private captures, real owner identities, phone/scooter identifiers,
  exact ride locations, personal filesystem paths and infrastructure secrets.
  Synthetic fixtures and public project URLs should be distinguishable from
  real user data. Rotate any exposed credential; deletion alone is insufficient.
- Keep signing keys, passwords, owner backups and local build/deployment files
  outside tracked source. Check the generated APK and website archive as well.
- Enable GitHub private vulnerability reporting when public visibility is
  enabled, then verify **Security → Report a vulnerability** works. The private
  repository's availability was not verified during this preparation; follow
  [SECURITY.md](../SECURITY.md)'s neutral-contact fallback until it is available.
- Confirm repository rules, CI permissions, secret access and contributor review
  settings for public pull requests. Do not run untrusted PR code with deployment
  or release-signing credentials.

## Asset and dependency inventory

| Component | Distribution and license |
| --- | --- |
| Volt application and helper source | MIT; [LICENSE](../LICENSE) |
| Referenced protocol implementation | MIT, Martin Pfeffer; reproduced in [NOTICE](../NOTICE) |
| Saira font files | SIL Open Font License 1.1; [full text](../licenses/Saira-OFL.txt) |
| Shizuku API/provider/AIDL/shared 13.1.5 | MIT, RikkaW; [full text](../licenses/Shizuku-MIT.txt) |
| AndroidX annotations 1.3.0 | Apache 2.0; [full text](../licenses/AndroidX-Apache-2.0.txt) |
| Browser ADB packages | MIT, Simon Chan; exact versions below; build emits full license texts |
| UT5 MAX photo | Temporary image obtained from the manufacturer's website; redistribution permission is not established; separate from the MIT source license |
| N65i photo | NAVEE product render supplied through a tester; redistribution permission is not established; separate from the MIT source license |
| ST3 Pro photo | NAVEE product image supplied by the maintainer; redistribution permission is not established; separate from the MIT source license |
| GT5 Max and GT3 Max photos | NAVEE product images supplied by the maintainer; redistribution permission is not established; separate from the MIT source license |
| Map tiles and data | Provider terms apply; no map data is bundled or relicensed as source |

**Release blocker:** the current `res/drawable-nodpi/ut5max.webp` was obtained
from the manufacturer's website. On 14 October 2026 the maintainer clarified
that permission was not established and chose to keep it as a temporary
placeholder while preparing an original photo. The N65i render added on 23
October 2026 (`res/drawable-nodpi/n65i.webp`), the ST3 Pro image
(`res/drawable-nodpi/st3pro.webp`), the GT5 Max and GT3 Max images
(`res/drawable-nodpi/gt5max.webp`, `gt3max.webp`) the UT5 Ultra X image
(`res/drawable-nodpi/ut5ultrax.webp`) and the XT5 Ultra image (`res/drawable-nodpi/xt5ultra.webp`) are in the
same position. Before
public distribution, replace each with an appropriately licensed original or
obtain verified permission.
Update the source asset, APK, screenshots and notices together. Older Git tags,
PR refs and release downloads also contain the placeholder; decide how to remove
or obtain permission for those copies before exposing the existing history.
Replacing only the current file does not resolve their distribution. The source's MIT
license does not grant rights to the current image or NAVEE's marks.

The browser dependency lock currently contains these runtime packages:

| Package | Version |
| --- | --- |
| `@yume-chan/adb` | 2.6.4 |
| `@yume-chan/adb-daemon-webusb` | 2.3.2 |
| `@yume-chan/async` | 4.1.3 |
| `@yume-chan/event` | 2.0.0 |
| `@yume-chan/no-data-view` | 2.0.0 |
| `@yume-chan/stream-extra` | 2.6.1 |
| `@yume-chan/struct` | 2.3.2 |

`@types/w3c-web-usb` 1.0.14 supplies MIT-licensed build-time type declarations;
esbuild 0.28.2 is an MIT-licensed build tool. Neither is shipped as browser runtime
code. `setup/web/build.mjs` collects the installed @yume-chan packages' license
texts and the Saira license into `THIRD-PARTY-NOTICES.txt`. Android builds package
`licenses/` as APK assets. Recheck this inventory whenever dependencies change.

## Before calling the release 1.0

- Complete independent review and required CI on the exact release commit.
- Verify the release APK's version, production signing certificate and checksum;
  retain the certificate so existing installations can upgrade in place.
- Test clean onboarding and retained-data upgrades, folded/unfolded dashboards,
  and a nearby parked scooter's connection and control readbacks.
- Record the tested phone, Android, NAVEE app and scooter firmware combinations.
  Current experience is focused on UT5 MAX; do not promise all-model support.
- Validate successful owner capture, cancellation, timeout, and interrupted
  cleanup for each setup method, and record incomplete real-device coverage in
  the setup guide. Chrome/USB is the maintainer's recommended default; this
  positioning does not add hardware-validation evidence.
- Recheck [Privacy](../PRIVACY.md) against behavior and production hosting.
  Local capture does not mean web servers or tile providers receive no requests.
- Publish source/asset notices, the changelog, limitations and backup advice with
  the release. Deployment and publication require maintainer authorization.

For the build and release procedure, see [RELEASING.md](../RELEASING.md).
