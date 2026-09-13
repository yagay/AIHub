#!/usr/bin/env python3
import argparse
import base64
import json
import pathlib
import shutil
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_RULES = ROOT / "chromium-overlay" / "assets" / "aihub" / "providers"


def load_rules(paths):
    rules = []
    ids = set()
    for path in paths:
        try:
            value = json.loads(path.read_text(encoding="utf-8"))
        except Exception as exc:
            raise SystemExit(f"invalid provider JSON {path}: {exc}") from exc
        if not isinstance(value, dict):
            raise SystemExit(f"provider rule must be a JSON object: {path}")
        provider_id = value.get("id")
        if not isinstance(provider_id, str) or not provider_id.strip():
            raise SystemExit(f"provider rule has no valid id: {path}")
        if provider_id in ids:
            raise SystemExit(f"duplicate provider id {provider_id!r}: {path}")
        ids.add(provider_id)
        rules.append(value)
    return rules


def main():
    parser = argparse.ArgumentParser(description="Build and Ed25519-sign an AIHub provider rule bundle")
    parser.add_argument("--version", type=int, required=True)
    parser.add_argument("--private-key", required=True, help="Ed25519 private key in PEM format")
    source = parser.add_mutually_exclusive_group()
    source.add_argument("--rules-dir", help="Directory containing provider *.json files")
    source.add_argument("--rules", nargs="+", help="Explicit provider JSON files")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    if args.version < 1:
        raise SystemExit("--version must be >= 1")
    if shutil.which("openssl") is None:
        raise SystemExit("openssl is required but was not found in PATH")

    private_key = pathlib.Path(args.private_key).expanduser().resolve()
    if not private_key.is_file():
        raise SystemExit(f"private key not found: {private_key}")

    if args.rules:
        paths = [pathlib.Path(item).expanduser().resolve() for item in args.rules]
        missing = [str(path) for path in paths if not path.is_file()]
        if missing:
            raise SystemExit("provider rule file(s) not found: " + ", ".join(missing))
    else:
        rules_dir = pathlib.Path(args.rules_dir or DEFAULT_RULES).expanduser().resolve()
        if not rules_dir.is_dir():
            raise SystemExit(f"provider rules directory not found: {rules_dir}")
        paths = sorted(rules_dir.glob("*.json"))

    if not paths:
        raise SystemExit("no provider JSON files selected")
    rules = load_rules(paths)

    # Keep the payload deterministic. Android verifies these exact bytes, so no JSON canonicalization
    # is required on the device.
    payload_obj = {"version": args.version, "rules": rules}
    payload = json.dumps(
        payload_obj,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")

    with tempfile.TemporaryDirectory(prefix="aihub-rules-") as tmp_name:
        tmp = pathlib.Path(tmp_name)
        payload_path = tmp / "payload.json"
        signature_path = tmp / "signature.bin"
        payload_path.write_bytes(payload)
        try:
            subprocess.run([
                "openssl", "pkeyutl", "-sign", "-rawin",
                "-inkey", str(private_key),
                "-in", str(payload_path),
                "-out", str(signature_path),
            ], check=True)
        except subprocess.CalledProcessError as exc:
            raise SystemExit(
                "OpenSSL signing failed. Ensure --private-key is an Ed25519 private key in PEM format."
            ) from exc
        signature = signature_path.read_bytes()

    envelope = {
        "payload": base64.b64encode(payload).decode("ascii"),
        "signature": base64.b64encode(signature).decode("ascii"),
    }
    output = pathlib.Path(args.output).expanduser()
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(envelope, indent=2) + "\n", encoding="utf-8")
    print(f"signed AIHub rule bundle v{args.version}: {output}")
    print(f"providers: {len(rules)}")
    print("provider ids: " + ", ".join(rule["id"] for rule in rules))


if __name__ == "__main__":
    main()
