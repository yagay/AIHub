#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 3 ]]; then
  echo "Usage: $0 /path/to/chromium/src [out/Default] [device-serial]" >&2
  exit 2
fi

CHROMIUM_SRC="$1"
OUT="${2:-out/Default}"
SERIAL="${3:-${ANDROID_SERIAL:-}}"
AIHUB_SOURCE="$(cd "$(dirname "$0")/.." && pwd)"

"$AIHUB_SOURCE/scripts/build_aihub_chromium.sh" "$CHROMIUM_SRC" "$OUT"

if [[ -n "$SERIAL" ]]; then
  "$AIHUB_SOURCE/scripts/install_aihub_local.sh" "$CHROMIUM_SRC" "$OUT" "$SERIAL"
else
  "$AIHUB_SOURCE/scripts/install_aihub_local.sh" "$CHROMIUM_SRC" "$OUT"
fi

echo
echo "AIHub development build/install flow completed successfully."
