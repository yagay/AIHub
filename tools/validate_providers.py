#!/usr/bin/env python3
import json
import pathlib
import re
from urllib.parse import urlparse

path = pathlib.Path("app/src/main/assets/providers.json")
try:
    providers = json.loads(path.read_text(encoding="utf-8"))
except Exception as exc:
    raise SystemExit(f"providers.json is invalid: {exc}")

if not isinstance(providers, list) or not providers:
    raise SystemExit("providers.json must be a non-empty array")

seen = set()
required_selector_groups = ("input", "send", "newChat", "stop", "attach")
for index, provider in enumerate(providers):
    if not isinstance(provider, dict):
        raise SystemExit(f"provider #{index} is not an object")
    provider_id = str(provider.get("id", "")).strip()
    name = str(provider.get("name", "")).strip()
    home_url = str(provider.get("homeUrl", "")).strip()
    hosts = provider.get("hosts")
    selectors = provider.get("selectors")
    ui = provider.get("ui", {})

    if not re.fullmatch(r"[a-z0-9][a-z0-9_-]*", provider_id):
        raise SystemExit(f"invalid provider id: {provider_id!r}")
    if provider_id in seen:
        raise SystemExit(f"duplicate provider id: {provider_id}")
    seen.add(provider_id)
    if not name:
        raise SystemExit(f"provider {provider_id} has no name")

    parsed = urlparse(home_url)
    if parsed.scheme != "https" or not parsed.hostname:
        raise SystemExit(f"provider {provider_id} homeUrl must be HTTPS")
    if not isinstance(hosts, list) or not hosts or not all(isinstance(x, str) and x.strip() for x in hosts):
        raise SystemExit(f"provider {provider_id} hosts must be a non-empty string array")
    normalized_hosts = [x.lower().strip() for x in hosts]
    hostname = parsed.hostname.lower()
    if not any(hostname == host or hostname.endswith("." + host) for host in normalized_hosts):
        raise SystemExit(f"provider {provider_id} hosts do not own homeUrl")

    if not isinstance(selectors, dict):
        raise SystemExit(f"provider {provider_id} selectors must be an object")
    for group in required_selector_groups:
        values = selectors.get(group)
        if not isinstance(values, list) or not all(isinstance(x, str) and x.strip() for x in values):
            raise SystemExit(f"provider {provider_id} selector group {group} must be a string array")
    if not selectors["input"]:
        raise SystemExit(f"provider {provider_id} must define at least one input selector")

    if not isinstance(ui, dict):
        raise SystemExit(f"provider {provider_id} ui must be an object")
    hidden = ui.get("hide", [])
    if not isinstance(hidden, list) or not all(isinstance(x, str) and x.strip() for x in hidden):
        raise SystemExit(f"provider {provider_id} ui.hide must be a string array")

    actions = ui.get("actions", [])
    if not isinstance(actions, list):
        raise SystemExit(f"provider {provider_id} ui.actions must be an array")
    action_ids = set()
    for action in actions:
        if not isinstance(action, dict):
            raise SystemExit(f"provider {provider_id} has a non-object APP action")
        action_id = str(action.get("id", "")).strip()
        label = str(action.get("label", "")).strip()
        action_selectors = action.get("selectors", [])
        keywords = action.get("keywords", [])
        if not re.fullmatch(r"[a-z0-9][a-z0-9_-]*", action_id):
            raise SystemExit(f"provider {provider_id} has invalid APP action id: {action_id!r}")
        if action_id in action_ids:
            raise SystemExit(f"provider {provider_id} has duplicate APP action: {action_id}")
        action_ids.add(action_id)
        if not label:
            raise SystemExit(f"provider {provider_id} APP action {action_id} has no label")
        if not isinstance(action_selectors, list) or not all(isinstance(x, str) and x.strip() for x in action_selectors):
            raise SystemExit(f"provider {provider_id} APP action {action_id} selectors must be a string array")
        if not isinstance(keywords, list) or not all(isinstance(x, str) and x.strip() for x in keywords):
            raise SystemExit(f"provider {provider_id} APP action {action_id} keywords must be a string array")
        if not action_selectors and not keywords:
            raise SystemExit(f"provider {provider_id} APP action {action_id} needs selectors or keywords")

print(f"Validated {len(providers)} providers: " + ", ".join(sorted(seen)))
