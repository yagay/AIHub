#!/usr/bin/env python3
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
errors = []

build = (ROOT / "chromium-overlay" / "BUILD.gn").read_text(encoding="utf-8")
for rel in re.findall(r'"((?:\.\./|java/|res/|assets/)[^"\n]+\.(?:java|aidl|xml|json))"', build):
    path = (ROOT / "chromium-overlay" / rel).resolve() if not rel.startswith("../") else (ROOT / "chromium-overlay" / rel).resolve()
    if not path.is_file():
        errors.append(f"BUILD.gn references missing file: {rel}")

if 'srcjar_deps = [ ":aihub_aidl" ]' not in build:
    errors.append("AIDL target must be wired through srcjar_deps")
if '//weblayer/public/java:webengine_java' not in build:
    errors.append("WebEngine public API dependency missing")

layout = (ROOT / "chromium-overlay/res/layout/aihub_activity_main.xml").read_text(encoding="utf-8")
strings_xml = ET.parse(ROOT / "chromium-overlay/res/values/strings.xml")
strings = {node.attrib["name"] for node in strings_xml.getroot().findall("string")}
activity = (ROOT / "chromium-overlay/java/com/yagay/aihub/chromium/AiHubShellActivity.java").read_text(encoding="utf-8")
for rid in sorted(set(re.findall(r'R\.id\.([A-Za-z0-9_]+)', activity))):
    if f'@+id/{rid}' not in layout and f'@id/{rid}' not in layout:
        errors.append(f"Activity references missing view id: {rid}")
for key in sorted(set(re.findall(r'R\.string\.([A-Za-z0-9_]+)', activity))):
    if key not in strings:
        errors.append(f"Activity references missing string: {key}")

manifest = ET.parse(ROOT / "chromium-overlay/AndroidManifest.xml")
android_ns = "{http://schemas.android.com/apk/res/android}"
for tag in ("activity", "service"):
    for node in manifest.getroot().findall(f".//{tag}"):
        name = node.attrib.get(android_ns + "name", "")
        if name.startswith("com.yagay.aihub.chromium."):
            cls = name.rsplit(".", 1)[1]
            path = ROOT / f"chromium-overlay/java/com/yagay/aihub/chromium/{cls}.java"
            if not path.is_file():
                errors.append(f"Manifest references missing class: {name}")

contract = (ROOT / "android-api/src/main/java/com/yagay/aihub/api/AiHubContract.java").read_text(encoding="utf-8")
parser = (ROOT / "chromium-overlay/java/com/yagay/aihub/chromium/AiHubExternalCommandParser.java").read_text(encoding="utf-8")
for action in re.findall(r'public static final String (ACTION_[A-Z_]+) =', contract):
    if action == "ACTION_BIND_SERVICE":
        continue
    if action not in parser:
        errors.append(f"External command parser does not handle {action}")

for script in (ROOT / "scripts").glob("*.sh"):
    text = script.read_text(encoding="utf-8")
    if not text.startswith("#!/usr/bin/env bash"):
        errors.append(f"Shell script missing bash shebang: {script.name}")

if errors:
    print("AIHub repository validation failed:")
    for error in errors:
        print(" -", error)
    sys.exit(1)
print("AIHub repository validation passed")
