#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "Usage: $0 /path/to/chromium/src [out/Default]" >&2
  exit 2
fi

CHROMIUM_SRC="$(cd "$1" && pwd)"
OUT="${2:-out/Default}"
AIHUB_SOURCE="$(cd "$(dirname "$0")/.." && pwd)"

"$AIHUB_SOURCE/scripts/sync_to_chromium.sh" "$CHROMIUM_SRC"
cd "$CHROMIUM_SRC"

if [[ ! -d "$OUT" ]]; then
  echo "Chromium output directory '$OUT' does not exist." >&2
  echo "Create it first with: gn args $OUT" >&2
  exit 3
fi

python3 aihub/scripts/check_chromium_compat.py "$CHROMIUM_SRC"
autoninja -C "$OUT" //aihub/chromium-overlay:aihub_local

echo "Build complete. AIHub APK should be under $OUT/apks/AIHub.apk"
echo "For local WebEngine development also install the built weblayer_support_apk."
