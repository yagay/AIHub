#!/usr/bin/env python3
import json
import pathlib
import re
from urllib.parse import urlparse

path = pathlib.Path("app/src/main/assets/providers.json")
providers = json.loads(path.read_text(encoding="utf-8"))
if not isinstance(providers, list) or not providers:
    raise SystemExit("providers.json must be a non-empty array")

ids = set()
models = set()
for item in providers:
    if not isinstance(item, dict):
        raise SystemExit("provider must be an object")
    provider_id = str(item.get("id", "")).strip()
    model = str(item.get("model", "")).strip()
    name = str(item.get("name", "")).strip()
    home = str(item.get("homeUrl", "")).strip()
    hosts = item.get("hosts")
    selectors = item.get("selectors")

    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]*", provider_id):
        raise SystemExit(f"invalid provider id: {provider_id!r}")
    if provider_id in ids:
        raise SystemExit(f"duplicate provider id: {provider_id}")
    ids.add(provider_id)
    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]*", model):
        raise SystemExit(f"invalid model id: {model!r}")
    if model in models:
        raise SystemExit(f"duplicate model id: {model}")
    models.add(model)
    if not name:
        raise SystemExit(f"provider {provider_id} has no name")

    parsed = urlparse(home)
    if parsed.scheme != "https" or not parsed.hostname:
        raise SystemExit(f"provider {provider_id} homeUrl must be HTTPS")
    if not isinstance(hosts, list) or not hosts:
        raise SystemExit(f"provider {provider_id} needs hosts")
    normalized = [str(x).strip().lower() for x in hosts]
    if not any(parsed.hostname.lower() == h or parsed.hostname.lower().endswith("." + h) for h in normalized):
        raise SystemExit(f"provider {provider_id} hosts do not own homeUrl")

    if not isinstance(selectors, dict):
        raise SystemExit(f"provider {provider_id} selectors must be an object")
    for key in ("input", "send", "stop", "assistant"):
        values = selectors.get(key)
        if not isinstance(values, list) or not values or not all(isinstance(v, str) and v.strip() for v in values):
            raise SystemExit(f"provider {provider_id} selector group {key} must be a non-empty string array")

required = {"chatgpt-web", "gemini-web", "claude-web", "deepseek-web", "grok-web"}
if models != required:
    raise SystemExit(f"expected exactly five browser models: {sorted(required)}, got {sorted(models)}")
print("Validated browser providers: " + ", ".join(sorted(ids)))
