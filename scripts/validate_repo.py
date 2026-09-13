#!/usr/bin/env python3
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

ROOT = pathlib.Path(__file__).resolve().parents[1]
errors = []

build = (ROOT / "chromium-overlay" / "BUILD.gn").read_text(encoding="utf-8")
for rel in re.findall(r'"((?:\.\./|java/|res/|assets/)[^"\n]+\.(?:java|aidl|xml|json|txt))"', build):
    path = (ROOT / "chromium-overlay" / rel).resolve()
    if not path.is_file():
        errors.append(f"BUILD.gn references missing file: {rel}")

if 'srcjar_deps = [ ":aihub_aidl" ]' not in build:
    errors.append("AIDL target must be wired through srcjar_deps")
if '//weblayer/public/java:webengine_java' not in build:
    errors.append("WebEngine public API dependency missing")
for required in (
    "AiHubEntryActivity.java",
    "AiHubShellActivity.java",
    "SignedProviderRuleBundle.java",
    "assets/aihub/rules_public_key.txt",
):
    if required not in build:
        errors.append(f"BUILD.gn is missing required AIHub source/asset: {required}")

layout = (ROOT / "chromium-overlay/res/layout/aihub_activity_main.xml").read_text(encoding="utf-8")
strings_xml = ET.parse(ROOT / "chromium-overlay/res/values/strings.xml")
strings = {node.attrib["name"] for node in strings_xml.getroot().findall("string")}
java_root = ROOT / "chromium-overlay/java/com/yagay/aihub/chromium"

for java_file in java_root.glob("*.java"):
    text = java_file.read_text(encoding="utf-8")
    for key in sorted(set(re.findall(r'(?<!android\.)R\.string\.([A-Za-z0-9_]+)', text))):
        if key not in strings:
            errors.append(f"{java_file.name} references missing string: {key}")

shell_activity = (java_root / "AiHubShellActivity.java").read_text(encoding="utf-8")
for rid in sorted(set(re.findall(r'(?<!android\.)R\.id\.([A-Za-z0-9_]+)', shell_activity))):
    if f'@+id/{rid}' not in layout and f'@id/{rid}' not in layout:
        errors.append(f"AiHubShellActivity references missing view id: {rid}")

manifest = ET.parse(ROOT / "chromium-overlay/AndroidManifest.xml")
manifest_root = manifest.getroot()
android_ns = "{http://schemas.android.com/apk/res/android}"
components = {}
for tag in ("activity", "service"):
    for node in manifest_root.findall(f".//{tag}"):
        name = node.attrib.get(android_ns + "name", "")
        if name.startswith("com.yagay.aihub.chromium."):
            components[name] = node
            cls = name.rsplit(".", 1)[1]
            path = java_root / f"{cls}.java"
            if not path.is_file():
                errors.append(f"Manifest references missing class: {name}")

entry_name = "com.yagay.aihub.chromium.AiHubEntryActivity"
shell_name = "com.yagay.aihub.chromium.AiHubShellActivity"
entry = components.get(entry_name)
shell = components.get(shell_name)
if entry is None:
    errors.append("Manifest must declare AiHubEntryActivity")
elif entry.attrib.get(android_ns + "exported") != "true":
    errors.append("AiHubEntryActivity must remain exported=true as the guarded external entry point")

if shell is None:
    errors.append("Manifest must declare AiHubShellActivity")
else:
    if shell.attrib.get(android_ns + "exported") != "false":
        errors.append("AiHubShellActivity must remain exported=false")
    if shell.findall("intent-filter"):
        errors.append("AiHubShellActivity must not expose intent filters")

contract = (ROOT / "android-api/src/main/java/com/yagay/aihub/api/AiHubContract.java").read_text(encoding="utf-8")
parser = (java_root / "AiHubExternalCommandParser.java").read_text(encoding="utf-8")
for action in re.findall(r'public static final String (ACTION_[A-Z_]+) =', contract):
    if action == "ACTION_BIND_SERVICE":
        continue
    if action not in parser:
        errors.append(f"External command parser does not handle {action}")

install_script = (ROOT / "scripts/install_aihub_local.sh").read_text(encoding="utf-8")
if "AiHubShellActivity" in install_script:
    errors.append("install_aihub_local.sh must launch the guarded entry activity, not AiHubShellActivity")
if "AiHubEntryActivity" not in install_script:
    errors.append("install_aihub_local.sh must launch AiHubEntryActivity")

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
