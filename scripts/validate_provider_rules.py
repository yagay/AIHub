#!/usr/bin/env python3
import json
import pathlib
import re
import sys
from urllib.parse import urlparse

ROOT = pathlib.Path(__file__).resolve().parents[1]
PROVIDERS = ROOT / "aihub-android" / "src" / "main" / "assets" / "aihub" / "providers"
INDEX = PROVIDERS / "index.txt"
KNOWN_CAPS = {"TEXT", "FILE_UPLOAD", "IMAGE_UPLOAD", "VOICE", "NEW_CHAT", "STOP", "REGENERATE", "WEB_SEARCH", "IMAGE_GENERATION"}

errors = []
ids = set()
orders = set()
files = sorted(PROVIDERS.glob("*.json"))

if not INDEX.is_file():
    errors.append("provider index is missing")
else:
    indexed = [line.strip() for line in INDEX.read_text(encoding="utf-8").splitlines()
               if line.strip() and not line.lstrip().startswith("#")]
    actual = [path.name for path in files]
    if sorted(indexed) != sorted(actual):
        errors.append(f"provider index mismatch: index={indexed!r}, files={actual!r}")

for path in files:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception as exc:
        errors.append(f"{path}: invalid JSON: {exc}")
        continue
    for key in ("id", "displayName", "homeUrl", "capabilities", "selectors", "order"):
        if key not in data:
            errors.append(f"{path}: missing {key}")
    pid = data.get("id")
    if not isinstance(pid, str) or not re.fullmatch(r"[a-z0-9_]+", pid or ""):
        errors.append(f"{path}: invalid id {pid!r}")
    elif pid in ids:
        errors.append(f"{path}: duplicate id {pid}")
    else:
        ids.add(pid)
    order = data.get("order")
    if not isinstance(order, int):
        errors.append(f"{path}: order must be int")
    elif order in orders:
        errors.append(f"{path}: duplicate order {order}")
    else:
        orders.add(order)
    url = data.get("homeUrl", "")
    parsed = urlparse(url)
    if parsed.scheme != "https" or not parsed.netloc:
        errors.append(f"{path}: homeUrl must be https: {url!r}")
    caps = data.get("capabilities", [])
    if not isinstance(caps, list) or any(cap not in KNOWN_CAPS for cap in caps):
        errors.append(f"{path}: invalid capabilities {caps!r}")
    selectors = data.get("selectors", {})
    if not isinstance(selectors, dict):
        errors.append(f"{path}: selectors must be an object")
    else:
        for key in ("input", "send", "newChat", "stop"):
            value = selectors.get(key, [])
            if not isinstance(value, list) or any(not isinstance(x, str) for x in value):
                errors.append(f"{path}: selectors.{key} must be a string list")

# The stable core must never grow provider-name branches.
core = ROOT / "aihub-core" / "src" / "main" / "java"
provider_names = ("chatgpt", "claude", "gemini", "grok", "deepseek")
for path in core.rglob("*.java"):
    text = path.read_text(encoding="utf-8").lower()
    if path.name == "BuiltinProviders.java":
        continue
    for name in provider_names:
        if name in text:
            errors.append(f"{path}: provider-specific name {name!r} leaked into stable core")

if errors:
    print("AIHub provider validation failed:")
    for error in errors:
        print(" -", error)
    sys.exit(1)

print(f"AIHub provider validation passed ({len(ids)} built-in providers)")
