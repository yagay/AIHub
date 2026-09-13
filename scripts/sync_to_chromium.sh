#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 ]]; then
  echo "Usage: $0 /path/to/chromium/src" >&2
  exit 2
fi

CHROMIUM_SRC="$(cd "$1" && pwd)"
AIHUB_SRC="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$CHROMIUM_SRC/aihub"

if [[ ! -f "$CHROMIUM_SRC/weblayer/public/java/BUILD.gn" ]]; then
  echo "This Chromium checkout does not expose //weblayer/public/java." >&2
  echo "Use a compatible Chromium revision or adapt chromium-overlay first." >&2
  exit 3
fi

if [[ "$AIHUB_SRC" == "$DEST" ]]; then
  echo "AIHub is already located at $DEST; no copy is needed."
else
  mkdir -p "$DEST"
  find "$DEST" -mindepth 1 -maxdepth 1 -exec rm -rf {} +
  cp -a "$AIHUB_SRC/." "$DEST/"
  rm -rf "$DEST/.git" "$DEST/.out"
  echo "AIHub synced to: $DEST"
fi

python3 "$DEST/scripts/check_chromium_compat.py" "$CHROMIUM_SRC"
echo "Build target: autoninja -C out/Default //aihub/chromium-overlay:aihub_local"
