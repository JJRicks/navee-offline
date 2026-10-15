#!/bin/bash
# Renders the link-preview card and app icons from the templates beside this script.
# Needs a local Google Chrome; the generated PNGs are committed under site/src/.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"; out="$here/../src"
chrome="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
shot() { "$chrome" --headless=new --disable-gpu --hide-scrollbars --force-device-scale-factor=1 --virtual-time-budget=4000 \
  --window-size="$2,$3" --screenshot="$4" "file://$here/$1" >/dev/null 2>&1; }
shot og.html 1200 630 "$out/og.png"
# Small headless windows can be clamped wider than requested, so render once at 512 and downscale.
shot icon.html 512 512 "$out/icon-512.png"
for size in 180:apple-touch-icon 192:icon-192 32:favicon-32; do
  ffmpeg -loglevel error -y -i "$out/icon-512.png" -vf "scale=${size%%:*}:${size%%:*}:flags=lanczos" "$out/${size#*:}.png"
done
echo "Rendered og.png and icons into $out"
