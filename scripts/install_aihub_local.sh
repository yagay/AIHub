#!/usr/bin/env bash
set -euo pipefail

if [[ $# -lt 1 || $# -gt 2 ]]; then
  echo "Usage: $0 /path/to/chromium/src [out/Default]" >&2
  exit 2
fi

CHROMIUM_SRC="$(cd "$1" && pwd)"
OUT="${2:-out/Default}"
cd "$CHROMIUM_SRC"

ADB="$CHROMIUM_SRC/third_party/android_sdk/public/platform-tools/adb"
if [[ ! -x "$ADB" ]]; then
  ADB="$(command -v adb || true)"
fi
if [[ -z "$ADB" ]]; then
  echo "adb not found" >&2
  exit 3
fi

# WebEngine local development commonly uses the support APK to provide the implementation.
if [[ -x "$OUT/bin/weblayer_support_apk" ]]; then
  "$OUT/bin/weblayer_support_apk" install
elif [[ -f "$OUT/apks/WebLayerSupport.apk" ]]; then
  "$ADB" install -r "$OUT/apks/WebLayerSupport.apk"
else
  echo "Warning: local WebEngine support APK was not found; the device WebView may provide the implementation." >&2
fi

if [[ -x "$OUT/bin/aihub_apk" ]]; then
  "$OUT/bin/aihub_apk" install
elif [[ -f "$OUT/apks/AIHub.apk" ]]; then
  "$ADB" install -r "$OUT/apks/AIHub.apk"
else
  echo "AIHub APK not found; run scripts/build_aihub_chromium.sh first." >&2
  exit 4
fi

"$ADB" shell am start -n com.yagay.aihub/com.yagay.aihub.chromium.AiHubShellActivity
