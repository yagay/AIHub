#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/.out"
rm -rf "$OUT"
mkdir -p "$OUT"
find "$ROOT/aihub-core/src/main/java" -name '*.java' -print0 | xargs -0 javac --release 17 -d "$OUT"
javac --release 17 -cp "$OUT" -d "$OUT" "$ROOT/aihub-core/src/test/java/com/yagay/aihub/core/CoreSmokeTest.java"
java -ea -cp "$OUT" com.yagay.aihub.core.CoreSmokeTest
