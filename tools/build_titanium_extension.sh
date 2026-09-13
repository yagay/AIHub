#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PROMETHEUS_COMMIT="e8eb08a2a6b57038fc0d657d00c621a5652483b9"
WORK="$ROOT/.build/prometheus"
OUT="$ROOT/app/src/main/assets/titanium-extension"

rm -rf "$WORK" "$OUT"
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

# Runtime updater compares this content hash with Titanium's installed copy.
BUILD_ID="$(cat "$OUT/manifest.json" "$OUT/service-worker.js" "$OUT/content.js" | sha256sum | awk '{print $1}')"
printf '%s\n' "$BUILD_ID" > "$OUT/AIHUB_BUILD_ID"

echo "Built Titanium extension $BUILD_ID from Prometheus@$PROMETHEUS_COMMIT (Browser-Tab mode only)"
