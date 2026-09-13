#!/usr/bin/env python3
"""Fail early when a Chromium checkout no longer matches AIHub's small WebEngine seam."""
import pathlib
import re
import sys

if len(sys.argv) != 2:
    print("Usage: check_chromium_compat.py /path/to/chromium/src", file=sys.stderr)
    raise SystemExit(2)

root = pathlib.Path(sys.argv[1]).resolve()
checks = [
    (root / "weblayer/public/java/BUILD.gn", r'android_library\("webengine_java"\)', "webengine_java target"),
    (root / "weblayer/public/java/org/chromium/webengine/Tab.java", r'executeScript\s*\(', "Tab.executeScript"),
    (root / "weblayer/public/java/org/chromium/webengine/Tab.java", r'getNavigationController\s*\(', "Tab.getNavigationController"),
    (root / "weblayer/public/java/org/chromium/webengine/FragmentParams.java", r'setProfileName\s*\(', "FragmentParams.setProfileName"),
    (root / "weblayer/public/java/org/chromium/webengine/FragmentParams.java", r'setPersistenceId\s*\(', "FragmentParams.setPersistenceId"),
    (root / "weblayer/public/java/org/chromium/webengine/WebSandbox.java", r'create\s*\(', "WebSandbox.create"),
    (root / "weblayer/shell/android/BUILD.gn", r'weblayer_support_apk', "local WebEngine support APK target"),
]

errors = []
for path, pattern, label in checks:
    if not path.is_file():
        errors.append(f"missing {path.relative_to(root)} ({label})")
        continue
    text = path.read_text(encoding="utf-8", errors="replace")
    if not re.search(pattern, text):
        errors.append(f"{label} not found in {path.relative_to(root)}")

rules = root / "build/config/android/rules.gni"
if not rules.is_file():
    errors.append("missing build/config/android/rules.gni")

if errors:
    print("AIHub Chromium compatibility check failed:")
    for error in errors:
        print(" -", error)
    print("Only chromium-overlay should need adaptation when WebEngine changes.")
    raise SystemExit(1)

print("AIHub Chromium compatibility check passed")
