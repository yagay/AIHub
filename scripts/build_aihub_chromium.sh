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

gn desc "$OUT" //aihub/chromium-overlay:aihub_apk >/dev/null

echo "Building AIHub + local WebEngine support..."
autoninja -C "$OUT" //aihub/chromium-overlay:aihub_local

AIHUB_APK="$(find "$OUT/apks" -maxdepth 2 -type f -name 'AIHub.apk' -print -quit 2>/dev/null || true)"
if [[ -z "$AIHUB_APK" ]]; then
  echo "Build completed but AIHub.apk was not found under $OUT/apks." >&2
  exit 7
fi

echo "Build complete: $CHROMIUM_SRC/$AIHUB_APK"
SUPPORT_APK="$(find "$OUT/apks" -maxdepth 2 -type f \( -iname '*weblayer*support*.apk' -o -iname '*webengine*support*.apk' \) -print -quit 2>/dev/null || true)"
if [[ -n "$SUPPORT_APK" ]]; then
  echo "WebEngine support APK: $CHROMIUM_SRC/$SUPPORT_APK"
else
  echo "Warning: no WebEngine support APK was found under $OUT/apks." >&2
fi
