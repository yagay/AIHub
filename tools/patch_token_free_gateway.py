#!/usr/bin/env python3
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1]).resolve()
manager = root / "src/browser/manager.ts"
text = manager.read_text(encoding="utf-8")
pattern = re.compile(
    r"\tprivate async tryAutoStartChrome\(\): Promise<void> \{.*?\n\t\}\n\}",
    re.S,
)
replacement = '''\tprivate async tryAutoStartChrome(): Promise<void> {\n\t\t// Android owns Chrome lifecycle and exposes CDP through the Root/LSPosed bridge.\n\t\tconsole.warn("[BrowserManager] Android runtime: Chrome auto-start is handled by AIHub");\n\t}\n}'''
text, count = pattern.subn(replacement, text, count=1)
if count != 1:
    raise SystemExit("token-free-gateway BrowserManager shape changed")
manager.write_text(text, encoding="utf-8")
print("Patched token-free-gateway for Android-owned Chrome lifecycle")
