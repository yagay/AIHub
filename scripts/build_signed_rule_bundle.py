#!/usr/bin/env python3
import argparse
import base64
import json
import pathlib
import subprocess
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
DEFAULT_RULES = ROOT / "chromium-overlay" / "assets" / "aihub" / "providers"


def main():
    parser = argparse.ArgumentParser(description="Build and Ed25519-sign an AIHub provider rule bundle")
    parser.add_argument("--version", type=int, required=True)
    parser.add_argument("--private-key", required=True, help="Ed25519 private key in PEM format")
    parser.add_argument("--rules-dir", default=str(DEFAULT_RULES))
    parser.add_argument("--output", required=True)
    args = parser.parse_args()

    if args.version < 1:
        raise SystemExit("--version must be >= 1")

    rules_dir = pathlib.Path(args.rules_dir)
    rules = []
    for path in sorted(rules_dir.glob("*.json")):
        rules.append(json.loads(path.read_text(encoding="utf-8")))
    if not rules:
        raise SystemExit(f"no provider JSON files found in {rules_dir}")

    payload_obj = {"version": args.version, "rules": rules}
    payload = json.dumps(payload_obj, ensure_ascii=False, sort_keys=True,
                         separators=(",", ":")).encode("utf-8")

    with tempfile.TemporaryDirectory(prefix="aihub-rules-") as tmp:
        tmp = pathlib.Path(tmp)
        payload_path = tmp / "payload.json"
        signature_path = tmp / "signature.bin"
        payload_path.write_bytes(payload)
        subprocess.run([
            "openssl", "pkeyutl", "-sign", "-rawin",
            "-inkey", str(pathlib.Path(args.private_key)),
            "-in", str(payload_path), "-out", str(signature_path),
        ], check=True)
        signature = signature_path.read_bytes()

    envelope = {
        "payload": base64.b64encode(payload).decode("ascii"),
        "signature": base64.b64encode(signature).decode("ascii"),
    }
    output = pathlib.Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(envelope, indent=2) + "\n", encoding="utf-8")
    print(f"signed AIHub rule bundle v{args.version}: {output}")
    print(f"providers: {len(rules)}")


if __name__ == "__main__":
    main()
