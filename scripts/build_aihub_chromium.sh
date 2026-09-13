#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "Usage: $0 /path/to/chromium/src [out/Default]" >&2
  exit 2
fi

CHROMIUM_SRC="$(cd "$1" && pwd)"
OUT="${2:-out/Default}"
AIHUB_SOURCE="$(cd "$(dirname "$0")/.." && pwd)"

python3 "$AIHUB_SOURCE/scripts/validate_provider_rules.py"
python3 "$AIHUB_SOURCE/scripts/validate_repo.py"
bash "$AIHUB_SOURCE/scripts/run_core_smoke_test.sh"
python3 "$AIHUB_SOURCE/scripts/check_chromium_checkout.py" "$CHROMIUM_SRC"

bash "$AIHUB_SOURCE/scripts/sync_to_chromium.sh" "$CHROMIUM_SRC"
python3 "$CHROMIUM_SRC/aihub/scripts/apply_chrome_overlay.py" "$CHROMIUM_SRC"
cd "$CHROMIUM_SRC"

if [[ ! -d "$OUT" || ! -f "$OUT/args.gn" ]]; then
  echo "Chromium output directory '$OUT' is not configured." >&2
  echo "Create it first with: gn args $OUT" >&2
  exit 3
fi

if ! grep -Eq '^[[:space:]]*target_os[[:space:]]*=[[:space:]]*"android"' "$OUT/args.gn"; then
  echo "$OUT/args.gn must contain: target_os = \"android\"" >&2
  exit 4
fi

if ! command -v autoninja >/dev/null 2>&1; then
  echo "autoninja not found. Add depot_tools to PATH." >&2
  exit 5
fi
if ! command -v gn >/dev/null 2>&1; then
  echo "gn not found. Add depot_tools to PATH." >&2
  exit 6
fi

gn desc "$OUT" //chrome/android:chrome_public_apk >/dev/null

echo "Building full Chromium Chrome with AIHub overlay..."
autoninja -C "$OUT" chrome_public_apk

CHROME_APK="$OUT/apks/ChromePublic.apk"
if [[ ! -f "$CHROME_APK" ]]; then
  echo "Build completed but $CHROME_APK was not found." >&2
  exit 7
fi

echo "Build complete: $CHROMIUM_SRC/$CHROME_APK"
echo "AIHub is embedded directly in the normal Chromium browser."
