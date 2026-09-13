#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
GATEWAY_COMMIT="769399b720f2826038d91df4cb6b5236735c220c"
NODE_MOBILE_VERSION="18.20.4"
WORK="$ROOT/.build/token-free-gateway"
NODE_WORK="$ROOT/.build/nodejs-mobile"
OUT="$ROOT/app/src/main/assets/nodejs-project"
JNI_OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"
HEADER_OUT="$ROOT/app/node-runtime/include/node"

rm -rf "$WORK" "$NODE_WORK" "$OUT" "$ROOT/app/node-runtime" "$JNI_OUT"
mkdir -p "$ROOT/.build" "$OUT/node_modules" "$JNI_OUT" "$HEADER_OUT"

git clone --filter=blob:none --no-checkout https://github.com/andeya/token-free-gateway.git "$WORK"
git -C "$WORK" checkout --detach "$GATEWAY_COMMIT"
python3 "$ROOT/tools/patch_token_free_gateway.py" "$WORK"
cp "$ROOT/mobile-runtime/gateway-entry.ts" "$WORK/mobile-entry.ts"

pushd "$WORK" >/dev/null
npm install --omit=dev --ignore-scripts --no-audit --no-fund
npx --yes esbuild@0.25.9 mobile-entry.ts \
  --bundle \
  --platform=node \
  --format=cjs \
  --target=node18 \
  --external:playwright-core \
  --outfile="$OUT/main.js"
cp -a node_modules/playwright-core "$OUT/node_modules/playwright-core"
cp LICENSE "$OUT/TOKEN_FREE_GATEWAY_LICENSE.txt"
popd >/dev/null

NODE_ZIP="$ROOT/.build/nodejs-mobile-v${NODE_MOBILE_VERSION}-android.zip"
curl -fL --retry 3 \
  "https://github.com/nodejs-mobile/nodejs-mobile/releases/download/v${NODE_MOBILE_VERSION}/nodejs-mobile-v${NODE_MOBILE_VERSION}-android.zip" \
  -o "$NODE_ZIP"
mkdir -p "$NODE_WORK"
unzip -q "$NODE_ZIP" -d "$NODE_WORK"

LIBNODE="$(find "$NODE_WORK" -type f -path '*/arm64-v8a/libnode.so' -print -quit)"
NODE_H="$(find "$NODE_WORK" -type f -path '*/include/node/node.h' -print -quit)"
if [[ -z "$LIBNODE" || -z "$NODE_H" ]]; then
  echo "Unable to locate arm64 libnode.so or Node headers in nodejs-mobile archive" >&2
  find "$NODE_WORK" -maxdepth 4 -type f | sort | head -200 >&2
  exit 1
fi

cp "$LIBNODE" "$JNI_OUT/libnode.so"
cp -a "$(dirname "$NODE_H")/." "$HEADER_OUT/"

cat > "$OUT/package.json" <<'JSON'
{
  "name": "aihub-mobile-gateway",
  "private": true,
  "version": "0.1.0",
  "main": "main.js"
}
JSON

printf '%s\n' "$GATEWAY_COMMIT" > "$OUT/UPSTREAM_TOKEN_FREE_GATEWAY_COMMIT"
printf '%s\n' "$NODE_MOBILE_VERSION" > "$OUT/NODEJS_MOBILE_VERSION"

test -s "$OUT/main.js"
test -f "$OUT/node_modules/playwright-core/package.json"
test -s "$JNI_OUT/libnode.so"
test -f "$HEADER_OUT/node.h"
echo "Prepared embedded gateway from token-free-gateway@$GATEWAY_COMMIT with Node.js Mobile $NODE_MOBILE_VERSION"
