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

AIHUB_APK=""
if [[ -d "$OUT/apks" ]]; then
  AIHUB_APK="$(find "$OUT/apks" -maxdepth 2 -type f -name 'AIHub.apk' -print -quit)"
fi
if [[ -z "$AIHUB_APK" ]]; then
  echo "AIHub.apk not found under $OUT/apks; run scripts/build_aihub_chromium.sh first." >&2
  exit 7
fi

# A local WebEngine build commonly uses the support APK. Install it when the target produced one.
SUPPORT_APK=""
if [[ -d "$OUT/apks" ]]; then
  SUPPORT_APK="$(find "$OUT/apks" -maxdepth 2 -type f \( -iname '*weblayer*support*.apk' -o -iname '*webengine*support*.apk' \) -print -quit)"
fi
if [[ -n "$SUPPORT_APK" ]]; then
  echo "Installing WebEngine support APK: $SUPPORT_APK"
  "${ADB_CMD[@]}" install -r -d "$SUPPORT_APK"
else
  echo "Warning: no local WebEngine support APK found under $OUT/apks." >&2
  echo "The device must provide a compatible WebEngine/WebView implementation." >&2
fi

echo "Installing AIHub APK: $AIHUB_APK"
"${ADB_CMD[@]}" install -r -d "$AIHUB_APK"

# Always enter through the exported guarded entry activity. The real shell intentionally remains unexported.
"${ADB_CMD[@]}" shell am start -W \
  -a android.intent.action.MAIN \
  -c android.intent.category.LAUNCHER \
  -n com.yagay.aihub/com.yagay.aihub.chromium.AiHubEntryActivity

echo "AIHub installed and launched through AiHubEntryActivity."
