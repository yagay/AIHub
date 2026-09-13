#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [[ -z "$SDK" ]]; then
  echo "ANDROID_SDK_ROOT or ANDROID_HOME is required" >&2
  exit 1
fi

NDK="${ANDROID_NDK_HOME:-}"
if [[ -z "$NDK" ]]; then
  NDK="$(find "$SDK/ndk" -mindepth 1 -maxdepth 1 -type d 2>/dev/null | sort -V | tail -n 1)"
fi
if [[ -z "$NDK" || ! -d "$NDK" ]]; then
  echo "Android NDK not found" >&2
  exit 1
fi

HOST_DIR="$(find "$NDK/toolchains/llvm/prebuilt" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
CXX="$HOST_DIR/bin/aarch64-linux-android28-clang++"
if [[ ! -x "$CXX" ]]; then
  echo "NDK compiler not found: $CXX" >&2
  exit 1
fi

OUT_DIR="$ROOT/app/src/main/assets/native"
mkdir -p "$OUT_DIR"
"$CXX" -std=c++17 -O2 -fPIE -pie -Wall -Wextra \
  "$ROOT/native/cdp_forwarder.cpp" \
  -o "$OUT_DIR/aihub_cdp_forwarder"
chmod 755 "$OUT_DIR/aihub_cdp_forwarder"
file "$OUT_DIR/aihub_cdp_forwarder"
