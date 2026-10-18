# Volt homepage

The public homepage at <https://volt.jjricks.com/>. It's a single static page with no build tools, no frameworks and no third-party requests. Fonts, images and scripts are all served from the same origin.

## Layout

- `src/` holds the page: `index.html`, `styles.css`, `main.js`, the icons, the link-preview card (`og.png`), the web manifest, `robots.txt` and `sitemap.xml`.
- `src/img/` holds the app screenshots, exported as WebP. They were captured on an emulator in simulation mode with sample data; the developer-mode banner is omitted, and the footer says the screens show sample data. The phone image retains the three-reading arrangement shared by the current default; Capacity experiment is below its viewport. The unfolded screenshot shows the Volt 1.4.4 default, including the full Capacity experiment card below Record ride, in a taller viewport. The sample name and existing readings are unchanged. Capacity values are simulated examples, not recorded evidence.
- `tools/` holds the templates and script that render `og.png` and the PNG icons with a local Google Chrome: `bash site/tools/render-images.sh`. The rendered files are committed, so a normal build doesn't need Chrome.
- `nginx/volt-homepage.conf` is the server configuration, with security headers, caching and the ACME webroot.

## Build

```sh
bash site/build.sh
```

This writes `site/dist/` and a SHA-256 manifest, `site/dist.sha256`, kept beside it rather than served. The Saira fonts and their SIL OFL license come straight from `res/font/` and `licenses/`, so they aren't duplicated. The download buttons link to GitHub Releases; this site doesn't host an APK.

## Deploy

The site is served from the jjricks.com VPS by its own nginx site, `volt-homepage`. Every other site's configuration is left untouched.

- Releases are immutable: `/var/www/volt-home/releases/<commit>/`, with `/var/www/volt-home/current` pointing at one of them.
- The certificate is a Let's Encrypt ECDSA certificate for `volt.jjricks.com`, issued and renewed with the webroot `/var/www/volt-home-acme`. The renew hook runs `nginx -t` before reloading nginx.
- Each deployment backs up `sites-available/` and `sites-enabled/` under `/etc/nginx/volt-deployment-backups/`.

Rollback:
- **To a previous release:** point `current` back at an earlier directory under `releases/`. No reload is needed.
- **To remove the site entirely:** delete `/etc/nginx/sites-enabled/volt-homepage`, then run `nginx -t && systemctl reload nginx`.

## Content policy

Volt is an independent project and must not present itself as NAVEE. Keep the non-affiliation notice in the FAQ and footer, and don't use NAVEE logos or branding. Feature claims should match `README.md` and `FEATURES.txt`.

Keep the page short enough to skim: what Volt does, model compatibility, download, setup, privacy and a short FAQ. Put details (counts, settings lists, implementation notes) in the repository docs, not on the page.

- Compatibility: describe Volt as an app for NAVEE scooters, with features varying by model and firmware. Keep the distinction between hardware-tested UT5 MAX support and other model mappings in the FAQ; don't position Volt as UT5-only or imply that every NAVEE scooter has been tested.
- Source: keep the GitHub repository link at the owner's request, including while it is private. The owner explicitly approved the prelaunch summary wording "independent, open source Android app"; don't replace it with "release planned." This copy approval does not authorize changing repository visibility. Setup links go to <https://pairscooter.jjricks.com/>. The public-release checklist remains in `docs/public-release.md`.
- Privacy: retain the owner's "Nothing leaves your phone" headline with a brief, legible asterisk note at the bottom-left inside the privacy box. Explain that map backgrounds are off by default and enabling them exposes the IP address and viewed map area to the provider. The Bluetooth core works offline, and neither exports nor backups are uploaded automatically.

The map footnote uses `map-data-note` for its ID and class. Avoid the generic `privacy-note` ID: EasyList Cookie hides it as a cookie-banner selector, even on ordinary page text.

The slide-to-lock demo is decorative (`aria-hidden`). Its knob, glyphs, labels and track tint run on one shared CSS cycle (`--lock-cycle`), started together by `main.js`. With reduced motion or without JavaScript it rests in the unlocked "Slide to lock" state.


## Animation efficiency

Decorative animations pause when the page is hidden, its window loses focus, or the page is suspended. Whole sections also pause when offscreen, preserving the shared lock-demo cycle. Visible animations resume from their paused position; reduced-motion and no-JavaScript fallbacks remain available.

The ambient particle canvas paints at most 60 times per second, with time-based movement on faster displays. Lightning paths are built once per strike; distant particle pairs skip expensive distance calculations. The route demo stops completely offscreen, retains its text nodes, and refreshes its numeric readout at most eight times per second while keeping the moving dot smooth.

When changing CSS or JavaScript, update their content-hash query strings in `src/index.html` so returning visitors receive the matching assets immediately. For lifecycle checks, include a real window focus change: browser automation can emulate focus or keep background pages reported as visible. CPU timings measure browser work, not power consumption.
