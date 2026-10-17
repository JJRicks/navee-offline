# Volt homepage

The public homepage at <https://volt.jjricks.com/>. It's a single static page with no build tools, no frameworks and no third-party requests. Fonts, images and scripts are all served from the same origin.

## Layout

- `src/` holds the page: `index.html`, `styles.css`, `main.js`, the icons, the link-preview card (`og.png`), the web manifest, `robots.txt` and `sitemap.xml`.
- `src/img/` holds the app screenshots, exported as WebP. They were captured on an emulator in simulation mode with sample data; the developer-mode banner is cropped out, and the footer says the screens show sample data.
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

Keep the page short enough to skim: what Volt does, what it's tested on, download, setup, privacy and a short FAQ. Put details (counts, settings lists, implementation notes) in the repository docs, not on the page.

- Compatibility: say "tested on the US UT5 MAX". Don't imply that other NAVEE models work until they're verified.
- Source: the repository is private for now, so don't call Volt open source or promise public source code. Don't link to repository files either; they 404 for visitors. Setup links go to the public helper at <https://pairscooter.jjricks.com/>. Revisit when it goes public (`docs/public-release.md`).
- Privacy: the Bluetooth core works offline; map backgrounds go online only after the user turns them on; exports and backups are deliberate. Don't say "nothing leaves your phone".

The slide-to-lock demo is decorative (`aria-hidden`). Its knob, glyphs, labels and track tint run on one shared CSS cycle (`--lock-cycle`), started together by `main.js`. With reduced motion or without JavaScript it rests in the unlocked "Slide to lock" state.
