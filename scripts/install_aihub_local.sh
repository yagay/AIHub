#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 3 ]]; then
  echo "Usage: $0 /path/to/chromium/src [out/Default] [device-serial]" >&2
  exit 2
fi

CHROMIUM_SRC="$(cd "$1" && pwd)"
OUT="${2:-out/Default}"
REQUESTED_SERIAL="${3:-${ANDROID_SERIAL:-}}"
cd "$CHROMIUM_SRC"

ADB="$CHROMIUM_SRC/third_party/android_sdk/public/platform-tools/adb"
if [[ ! -x "$ADB" ]]; then
  ADB="$(command -v adb || true)"
fi
if [[ -z "$ADB" || ! -x "$ADB" ]]; then
  echo "adb not found. Run Chromium's build/android/envsetup.sh or install Android platform-tools." >&2
  exit 3
fi

SERIAL="$REQUESTED_SERIAL"
if [[ -z "$SERIAL" ]]; then
  mapfile -t DEVICES < <("$ADB" devices | awk 'NR > 1 && $2 == "device" {print $1}')
  if [[ ${#DEVICES[@]} -eq 0 ]]; then
    echo "No authorized Android device/emulator found." >&2
    exit 4
  fi
  if [[ ${#DEVICES[@]} -gt 1 ]]; then
    echo "Multiple Android devices found. Pass the device serial as the third argument or set ANDROID_SERIAL." >&2
    printf ' - %s\n' "${DEVICES[@]}" >&2
    exit 5
  fi
  SERIAL="${DEVICES[0]}"
fi

ADB_CMD=("$ADB" -s "$SERIAL")
if [[ "$("${ADB_CMD[@]}" get-state 2>/dev/null || true)" != "device" ]]; then
  echo "Android device '$SERIAL' is not ready/authorized." >&2
  exit 6
fi

echo "Using Android device: $SERIAL"

CHROME_APK="$OUT/apks/ChromePublic.apk"
CHROME_RUNNER="$OUT/bin/chrome_public_apk"
if [[ ! -f "$CHROME_APK" || ! -x "$CHROME_RUNNER" ]]; then
  echo "ChromePublic.apk/runner not found; run scripts/build_aihub_chromium.sh first." >&2
  exit 7
fi

# Chromium's generated runner knows the package/activity details for this exact build.
ANDROID_SERIAL="$SERIAL" "$CHROME_RUNNER" install
ANDROID_SERIAL="$SERIAL" "$CHROME_RUNNER" launch

echo "Chromium Chrome with AIHub overlay installed and launched."
