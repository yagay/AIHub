#!/usr/bin/env python3
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
errors: list[str] = []

core_root = ROOT / "aihub-core" / "src" / "main" / "java"
android_root = ROOT / "aihub-android" / "java"
seam_root = ROOT / "chromium-overlay" / "java" / "com" / "yagay" / "aihub" / "chromium"
provider_root = ROOT / "chromium-overlay" / "assets" / "aihub" / "providers"


def text(path: pathlib.Path) -> str:
    return path.read_text(encoding="utf-8")


def require_file(relative: str) -> pathlib.Path:
    path = ROOT / relative
    if not path.is_file():
        errors.append(f"Missing required file: {relative}")
    return path


# Required architecture surface.
required_files = (
    "aihub-android/java/com/yagay/aihub/android/AiHubBrowserHost.java",
    "aihub-android/java/com/yagay/aihub/android/BrowserSessionRuntime.java",
    "aihub-android/java/com/yagay/aihub/android/AiHubUiCoordinator.java",
    "aihub-android/java/com/yagay/aihub/android/GenericDomScriptFactory.java",
    "aihub-android/java/com/yagay/aihub/android/ProviderRuleLoader.java",
    "chromium-overlay/java/com/yagay/aihub/chromium/AiHubChromeBridge.java",
    "chromium-overlay/java/com/yagay/aihub/chromium/AiHubChromeHook.java",
    "chromium-overlay/assets/aihub/rules_public_key.txt",
    "scripts/apply_chrome_overlay.py",
    "scripts/check_chromium_checkout.py",
    "scripts/build_aihub_chromium.sh",
    "scripts/install_aihub_local.sh",
    "docs/ARCHITECTURE.md",
    "docs/CHROMIUM_INTEGRATION.md",
    "docs/INTEGRATION_TEST_CHECKLIST.md",
)
for relative in required_files:
    require_file(relative)

# Standalone WebEngine APK architecture must not return.
for obsolete in (
    "chromium-overlay/AndroidManifest.xml",
    "chromium-overlay/BUILD.gn",
    "chromium-overlay/res/layout/aihub_activity_main.xml",
    "chromium-overlay/java/com/yagay/aihub/chromium/AiWebEngineHost.java",
    "chromium-overlay/java/com/yagay/aihub/chromium/WebEngineSessionRuntime.java",
    "chromium-overlay/java/com/yagay/aihub/chromium/AiHubShellActivity.java",
    "chromium-overlay/java/com/yagay/aihub/chromium/AiHubEntryActivity.java",
    "android-api/src/main/aidl/com/yagay/aihub/api/IAiHubService.aidl",
):
    if (ROOT / obsolete).exists():
        errors.append(f"Obsolete standalone/WebEngine file returned: {obsolete}")

# Provider configuration remains JSON-driven.
provider_files = sorted(provider_root.glob("*.json")) if provider_root.is_dir() else []
if len(provider_files) < 5:
    errors.append("Expected at least five built-in provider JSON rules")
for expected in ("chatgpt", "claude", "gemini", "grok", "deepseek"):
    if not (provider_root / f"{expected}.json").is_file():
        errors.append(f"Missing built-in provider rule: {expected}.json")

# Stable core must stay pure Java and provider-only.
for path in core_root.rglob("*.java"):
    source = text(path)
    for forbidden_import in (
        "import android.",
        "import androidx.",
        "import org.chromium.",
    ):
        if forbidden_import in source:
            errors.append(f"Core leaked platform/browser dependency: {path.relative_to(ROOT)}")
    for forbidden in (
        "AiAccount", "AccountRegistry", "AiWorkspace", "WorkspaceRegistry",
        "accountId", "workspaceId", "EXTRA_ACCOUNT_ID", "EXTRA_WORKSPACE_ID",
    ):
        if forbidden in source:
            errors.append(f"Provider-only core contains {forbidden}: {path.relative_to(ROOT)}")

# Stable Android layer may use Android APIs but never Chromium internals.
chromium_import = re.compile(
    r'^\s*import\s+org\.chromium\.(?:chrome|content_public|webengine)(?:\.|;)',
    re.MULTILINE,
)
for path in android_root.rglob("*.java"):
    source = text(path)
    if chromium_import.search(source):
        errors.append(f"Chromium API leaked into stable Android layer: {path.relative_to(ROOT)}")
    if "org.chromium.webengine" in source:
        errors.append(f"Legacy WebEngine reference in stable Android layer: {path.relative_to(ROOT)}")

# Exactly two Java files form the Chromium seam.
if seam_root.is_dir():
    seam_files = sorted(path.name for path in seam_root.glob("*.java"))
else:
    seam_files = []
expected_seam = ["AiHubChromeBridge.java", "AiHubChromeHook.java"]
if seam_files != expected_seam:
    errors.append(f"Chromium seam must contain only {expected_seam}; found {seam_files}")

bridge_path = seam_root / "AiHubChromeBridge.java"
hook_path = seam_root / "AiHubChromeHook.java"
if bridge_path.is_file():
    bridge = text(bridge_path)
    for required in (
        "implements AiHubBrowserHost",
        "ChromeTabbedActivity",
        "TabModelSelector",
        "TabModelUtils.runOnTabStateInitialized",
        "createNewTab(",
        "executeJavaScriptInIsolatedWorld(",
        "getMainFrame()",
        "getCurrentTab()",
        "TabClosureParams.closeTab(",
    ):
        if required not in bridge:
            errors.append(f"AiHubChromeBridge missing required current-Chrome seam: {required}")
    if "org.chromium.webengine" in bridge or "weblayer" in bridge.lower():
        errors.append("AiHubChromeBridge must not depend on legacy WebEngine/WebLayer")

if hook_path.is_file():
    hook = text(hook_path)
    if "AiHubUiCoordinator.attachConfigured" not in hook:
        errors.append("AiHubChromeHook must start the configured provider/UI coordinator")
    imported_chromium = re.findall(r'^\s*import\s+(org\.chromium\.[^;]+);', hook, re.MULTILINE)
    if imported_chromium != ["org.chromium.chrome.browser.ChromeTabbedActivity"]:
        errors.append(
            "AiHubChromeHook must depend on only ChromeTabbedActivity; "
            f"found Chromium imports {imported_chromium}")

runtime_path = android_root / "com" / "yagay" / "aihub" / "android" / "BrowserSessionRuntime.java"
if runtime_path.is_file():
    runtime = text(runtime_path)
    if "implements SessionRuntime" not in runtime:
        errors.append("BrowserSessionRuntime must implement stable SessionRuntime")
    for method in (
        "open(", "activate(", "close(", "sendText(", "newChat(", "stop(",
        "attach(", "attachAndSend(", "back(", "forward(", "reload(",
    ):
        if method not in runtime:
            errors.append(f"BrowserSessionRuntime missing SessionRuntime operation {method}")
    if "openAttachmentChooser()" not in runtime:
        errors.append("Normal attachment flow must use the website/Chrome native file chooser")

ui_path = android_root / "com" / "yagay" / "aihub" / "android" / "AiHubUiCoordinator.java"
if ui_path.is_file():
    ui = text(ui_path)
    for required in (
        "attachConfigured(", "＋ AI", "CustomProviderFactory.create(",
        "showChromeControls(", "sessions.sendText(",
    ):
        if required not in ui:
            errors.append(f"AI-first UI missing expected capability: {required}")

# Overlay patcher is the only source-list/activity mutation path.
patcher_path = ROOT / "scripts" / "apply_chrome_overlay.py"
if patcher_path.is_file():
    patcher = text(patcher_path)
    for required in (
        "AIHUB-SOURCES-BEGIN",
        "AIHUB-SOURCES-END",
        "AiHubChromeHook.attach(this)",
        "performPostInflationStartup()",
        "mControlContainer = findViewById(R.id.control_container);",
        "AiHubGeneratedRules.java",
        "chromium-overlay\" / \"assets\" / \"aihub\" / \"providers",
    ):
        if required not in patcher:
            errors.append(f"Chrome overlay patcher missing invariant: {required}")
    if "weblayer" in patcher.lower() or "webengine" in patcher.lower():
        errors.append("Chrome overlay patcher must not target WebLayer/WebEngine")

# Build/install flow must produce and run the full Chromium browser.
build_script = text(ROOT / "scripts" / "build_aihub_chromium.sh")
install_script = text(ROOT / "scripts" / "install_aihub_local.sh")
for required in ("chrome_public_apk", "ChromePublic.apk", "apply_chrome_overlay.py"):
    if required not in build_script:
        errors.append(f"build_aihub_chromium.sh missing {required}")
for forbidden in ("aihub_apk", "weblayer_support", "webengine", "AIHub.apk"):
    if forbidden.lower() in build_script.lower():
        errors.append(f"build_aihub_chromium.sh still contains legacy target {forbidden}")
if "$OUT/bin/chrome_public_apk" not in install_script:
    errors.append("install_aihub_local.sh must use Chromium's chrome_public_apk runner")
for forbidden in ("AiHubEntryActivity", "AIHub.apk", "WebEngine", "WebLayer"):
    if forbidden.lower() in install_script.lower():
        errors.append(f"install_aihub_local.sh still contains standalone/WebEngine path: {forbidden}")

# Chromium compatibility checker must target current Chrome Android, not removed WebLayer.
checker = text(ROOT / "scripts" / "check_chromium_checkout.py")
for required in (
    "ChromeTabbedActivity", "TabModelSelector", "TabModelUtils", "RenderFrameHost",
    "executeJavaScriptInIsolatedWorld(", "chrome_java_sources.gni", "chrome_public_apk",
):
    if required not in checker:
        errors.append(f"Chromium compatibility checker missing {required}")

# Shell/Python syntax conventions and no old standalone identifiers in active scripts.
for path in (ROOT / "scripts").glob("*.sh"):
    source = text(path)
    if not source.startswith("#!/usr/bin/env bash"):
        errors.append(f"Shell script missing bash shebang: {path.name}")
    for legacy in ("AiHubEntryActivity", "aihub_apk", "weblayer_support_apk"):
        if legacy in source:
            errors.append(f"Shell script still contains legacy identifier {legacy}: {path.name}")

if errors:
    print("AIHub repository validation failed:")
    for error in errors:
        print(" -", error)
    sys.exit(1)

print("AIHub repository validation passed")
print("architecture: aihub-core -> aihub-android -> AiHubChromeBridge/Hook -> chrome_public_apk")
