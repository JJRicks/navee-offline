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
for file in index.html styles.css main.js favicon.svg favicon-32.png apple-touch-icon.png icon-192.png icon-512.png \
            og.png site.webmanifest robots.txt sitemap.xml; do
  cp "$src/$file" "$dist/$file"
done
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
