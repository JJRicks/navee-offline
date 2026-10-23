#!/bin/bash
# Assembles the static Volt homepage into site/dist/. Bash and coreutils only; no network access.
# Downloads link to GitHub Releases, so no APK is published from this site.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$here/.."
src="$here/src"
dist="$here/dist"

rm -rf "$dist"
mkdir -p "$dist/fonts" "$dist/img"
for file in styles.css main.js favicon.svg favicon-32.png apple-touch-icon.png icon-192.png icon-512.png \
            og.png site.webmanifest robots.txt sitemap.xml; do
  cp "$src/$file" "$dist/$file"
done
# The download line names the app's own version, so the page can't fall behind a release.
version="$(sed -n 's/.*android:versionName="\([0-9][0-9]*\.[0-9][0-9]*\.[0-9][0-9]*\)".*/\1/p' "$repo/AndroidManifest.xml" | head -n 1)"
[ -n "$version" ] || { echo "No versionName in AndroidManifest.xml" >&2; exit 1; }
grep -q '@VERSION@' "$src/index.html" || { echo "index.html has no @VERSION@ placeholder" >&2; exit 1; }
sed "s/@VERSION@/$version/g" "$src/index.html" > "$dist/index.html"
if grep -q '@VERSION@' "$dist/index.html"; then echo "Unreplaced @VERSION@ in index.html" >&2; exit 1; fi
cp "$src"/img/*.webp "$dist/img/"
for weight in regular medium semibold bold; do
  cp "$repo/res/font/saira_$weight.ttf" "$dist/fonts/saira-$weight.ttf"
done
{
  printf 'Volt homepage: third-party notices\n\n'
  printf '=== Saira typeface (regular, medium, semibold, bold) ===\n'
  cat "$repo/licenses/Saira-OFL.txt"
} > "$dist/THIRD-PARTY-NOTICES.txt"

# Publication manifest, kept beside dist/ rather than served.
(cd "$dist" && find . -type f | LC_ALL=C sort | sed 's|^\./||' | while read -r file; do shasum -a 256 "$file"; done) > "$here/dist.sha256"
echo "Built $dist ($(wc -l < "$here/dist.sha256" | tr -d ' ') files)"
