#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROMETHEUS_COMMIT="e8eb08a2a6b57038fc0d657d00c621a5652483b9"
WORK="$ROOT/.build/prometheus"
OUT="$ROOT/app/src/main/assets/titanium-extension"
KEYSTORE="$ROOT/.build/aihub-debug.keystore"
PKCS12="$ROOT/.build/aihub-extension.p12"
PEM="$ROOT/.build/aihub-extension.pem"
CRX_TMP="$ROOT/.build/aihub-bridge.crx"

rm -rf "$WORK" "$OUT" "$KEYSTORE" "$PKCS12" "$PEM" "$CRX_TMP"
mkdir -p "$ROOT/.build" "$OUT"
git clone --filter=blob:none --no-checkout https://github.com/fibbersha-hub/prometheus-ai-orchestrator.git "$WORK"
git -C "$WORK" checkout --detach "$PROMETHEUS_COMMIT"

cp "$WORK/content/content.js" "$OUT/content.js"
cp "$ROOT/extension/manifest.json" "$OUT/manifest.json"
cp "$ROOT/extension/service-worker.js" "$OUT/service-worker.js"
cp "$WORK/LICENSE" "$OUT/PROMETHEUS_LICENSE.txt"
python3 "$ROOT/tools/patch_prometheus.py" "$OUT/content.js"
printf '%s\n' "$PROMETHEUS_COMMIT" > "$OUT/UPSTREAM_PROMETHEUS_COMMIT"

node -e 'JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"))' "$OUT/manifest.json"
test -s "$OUT/content.js"

# Keep one stable extension identity without storing a second standalone private key.
# The already-retained AIHub debug signing keystore is converted only inside CI.
base64 --decode "$ROOT/ci/aihub-debug.keystore.b64" > "$KEYSTORE"
keytool -importkeystore \
  -srckeystore "$KEYSTORE" -srcstorepass android -srcalias androiddebugkey -srckeypass android \
  -destkeystore "$PKCS12" -deststoretype PKCS12 -deststorepass android -destkeypass android \
  -noprompt >/dev/null
openssl pkcs12 -in "$PKCS12" -nodes -nocerts -passin pass:android -out "$PEM" >/dev/null 2>&1

PUB_HASH="$(openssl rsa -in "$PEM" -pubout -outform DER 2>/dev/null | sha256sum | awk '{print $1}')"
EXTENSION_ID="$(python3 - "$PUB_HASH" <<'PY'
import sys
h = sys.argv[1][:32]
print(''.join(chr(ord('a') + int(ch, 16)) for ch in h))
PY
)"
VERSION="$(node -e 'console.log(JSON.parse(require("fs").readFileSync(process.argv[1],"utf8")).version)' "$OUT/manifest.json")"

# Runtime updater compares this source hash. CRX zip timestamps do not affect it.
BUILD_ID="$(cat "$OUT/manifest.json" "$OUT/service-worker.js" "$OUT/content.js" | sha256sum | awk '{print $1}')"
printf '%s\n' "$BUILD_ID" > "$OUT/AIHUB_BUILD_ID"
printf '%s\n' "$EXTENSION_ID" > "$OUT/EXTENSION_ID"

# Titanium's Android fork reads Chromium external-extension prefs from
# <DIR_USER_DATA>/extensions. Build a CRX3 plus the matching standalone JSON.
npx --yes crx@5.0.1 pack "$OUT" --crx-version 3 -p "$PEM" -o "$CRX_TMP" -b 10485760 >/dev/null
cp "$CRX_TMP" "$OUT/$EXTENSION_ID.crx"
cat > "$OUT/$EXTENSION_ID.json" <<JSON
{
  "external_crx": "$EXTENSION_ID.crx",
  "external_version": "$VERSION"
}
JSON

# The temporary signing material must never enter Android assets.
rm -f "$KEYSTORE" "$PKCS12" "$PEM" "$CRX_TMP"

test -s "$OUT/$EXTENSION_ID.crx"
node -e 'JSON.parse(require("fs").readFileSync(process.argv[1],"utf8"))' "$OUT/$EXTENSION_ID.json"
printf 'Built Titanium external extension %s v%s build=%s from Prometheus@%s\n' \
  "$EXTENSION_ID" "$VERSION" "$BUILD_ID" "$PROMETHEUS_COMMIT"
