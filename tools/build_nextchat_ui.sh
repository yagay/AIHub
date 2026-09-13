#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NEXTCHAT_COMMIT="defdcdb55d850cd12c4c657eb83729fd66e215c0"
WORK="${AIHUB_NEXTCHAT_WORKDIR:-$ROOT/.build/nextchat}"
OUT="$ROOT/app/src/main/assets/ui"

rm -rf "$WORK" "$OUT"
mkdir -p "$(dirname "$WORK")" "$OUT"

git clone --filter=blob:none --no-checkout https://github.com/ChatGPTNextWeb/NextChat.git "$WORK"
git -C "$WORK" checkout --detach "$NEXTCHAT_COMMIT"

python3 "$ROOT/ui/nextchat/patch_nextchat.py" "$WORK"

cd "$WORK"
export HUSKY=0
export NEXT_TELEMETRY_DISABLED=1
corepack enable >/dev/null 2>&1 || true
corepack prepare yarn@1.22.19 --activate >/dev/null 2>&1 || true
yarn install --frozen-lockfile --network-timeout 600000
yarn export

cp -a out/. "$OUT/"
cp "$ROOT/ui/nextchat/NEXTCHAT_LICENSE.txt" "$OUT/NEXTCHAT_LICENSE.txt"

test -f "$OUT/index.html"
test -d "$OUT/_next"
echo "NextChat UI built from $NEXTCHAT_COMMIT for AIHub local gateway"
