#!/usr/bin/env python3
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
errors: list[str] = []

CORE = ROOT / "aihub-core" / "src" / "main" / "java"
ANDROID = ROOT / "aihub-android" / "java"
APP = ROOT / "app" / "src" / "main"
PROVIDERS = ROOT / "aihub-android" / "src" / "main" / "assets" / "aihub" / "providers"


def read(path: pathlib.Path) -> str:
    return path.read_text(encoding="utf-8")


def require(relative: str) -> pathlib.Path:
    path = ROOT / relative
    if not path.is_file():
        errors.append(f"Missing required file: {relative}")
    return path


required = (
    "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/com/yagay/aihub/app/MainActivity.java",
    "app/src/main/java/com/yagay/aihub/app/WebViewBrowserHost.java",
    "aihub-android/build.gradle.kts",
    "aihub-android/java/com/yagay/aihub/android/AiHubBrowserHost.java",
    "aihub-android/java/com/yagay/aihub/android/BrowserSessionRuntime.java",
    "aihub-android/java/com/yagay/aihub/android/AiHubUiCoordinator.java",
    "aihub-android/java/com/yagay/aihub/android/GenericDomScriptFactory.java",
    "aihub-android/java/com/yagay/aihub/android/ProviderRuleLoader.java",
    "aihub-android/src/main/assets/aihub/providers/index.txt",
    "aihub-android/src/main/assets/aihub/rules_public_key.txt",
)
for item in required:
    require(item)

# The old full-Chromium build stays only on archive/chromium-overlay, never on main.
for obsolete in (
    "chromium-overlay",
    "chromium.version",
    ".github/workflows/build-chromium.yml",
    "scripts/apply_chrome_overlay.py",
    "scripts/build_aihub_chromium.sh",
    "scripts/build_install_aihub.sh",
    "scripts/check_chromium_checkout.py",
    "scripts/install_aihub_local.sh",
    "scripts/sync_to_chromium.sh",
    "scripts/test_chrome_overlay.py",
):
    if (ROOT / obsolete).exists():
        errors.append(f"Archived Chromium-only path must not exist on main: {obsolete}")

# Provider rules are packaged Android assets and indexed explicitly.
provider_files = sorted(PROVIDERS.glob("*.json")) if PROVIDERS.is_dir() else []
expected = {"chatgpt.json", "claude.json", "gemini.json", "grok.json", "deepseek.json"}
actual = {path.name for path in provider_files}
if not expected.issubset(actual):
    errors.append(f"Missing built-in provider assets: {sorted(expected - actual)}")
if (PROVIDERS / "index.txt").is_file():
    indexed = {
        line.strip() for line in read(PROVIDERS / "index.txt").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    }
    if indexed != actual:
        errors.append(f"Provider index does not match JSON assets: index={sorted(indexed)}, files={sorted(actual)}")

# Core stays pure Java and provider-only.
for path in CORE.rglob("*.java"):
    source = read(path)
    for forbidden in ("import android.", "import androidx.", "import org.chromium."):
        if forbidden in source:
            errors.append(f"Core leaked platform dependency: {path.relative_to(ROOT)}")
    for forbidden in (
        "AiAccount", "AccountRegistry", "AiWorkspace", "WorkspaceRegistry",
        "accountId", "workspaceId", "EXTRA_ACCOUNT_ID", "EXTRA_WORKSPACE_ID",
    ):
        if forbidden in source:
            errors.append(f"Provider-only core contains {forbidden}: {path.relative_to(ROOT)}")

# Stable Android layer is browser-engine neutral. WebView itself belongs in :app.
for path in ANDROID.rglob("*.java"):
    source = read(path)
    if re.search(r'^\s*import\s+org\.chromium\.', source, re.MULTILINE):
        errors.append(f"Chromium API leaked into stable Android layer: {path.relative_to(ROOT)}")
    if re.search(r'^\s*import\s+android\.webkit\.WebView\s*;', source, re.MULTILINE):
        errors.append(f"Concrete WebView leaked into stable Android layer: {path.relative_to(ROOT)}")

host_path = APP / "java" / "com" / "yagay" / "aihub" / "app" / "WebViewBrowserHost.java"
if host_path.is_file():
    host = read(host_path)
    for required_text in (
        "implements AiHubBrowserHost",
        "new WebView(activity)",
        "CookieManager.getInstance()",
        "onShowFileChooser(",
        "PermissionRequest",
        "onGeolocationPermissionsShowPrompt(",
        "onCreateWindow(",
        "onCloseWindow(",
        "DownloadManager",
        "onRenderProcessGone(",
        "evaluateJavascript(",
        "setSafeBrowsingEnabled(true)",
    ):
        if required_text not in host:
            errors.append(f"WebViewBrowserHost missing browser capability: {required_text}")
    if "setJavaScriptEnabled(true)" not in host or "setDomStorageEnabled(true)" not in host:
        errors.append("WebView browser must enable JavaScript and DOM storage for AI sites")
    if "setAllowFileAccess(false)" not in host:
        errors.append("WebView browser must keep direct file:// access disabled")
    if "MIXED_CONTENT_NEVER_ALLOW" not in host:
        errors.append("WebView browser must reject mixed content")

manifest_path = APP / "AndroidManifest.xml"
if manifest_path.is_file():
    manifest = read(manifest_path)
    for permission in (
        "android.permission.INTERNET",
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.ACCESS_FINE_LOCATION",
    ):
        if permission not in manifest:
            errors.append(f"Manifest missing browser capability permission: {permission}")
    if 'android:usesCleartextTraffic="false"' not in manifest:
        errors.append("App must keep cleartext traffic disabled")

app_gradle = ROOT / "app" / "build.gradle.kts"
if app_gradle.is_file():
    source = read(app_gradle)
    for expected_text in (
        'applicationId = "com.yagay.aihub"',
        "minSdk = 31",
        "compileSdk = 36",
        "targetSdk = 36",
        'implementation(project(":aihub-android"))',
        'androidx.webkit:webkit:1.17.0',
    ):
        if expected_text not in source:
            errors.append(f"App Gradle missing invariant: {expected_text}")

settings = ROOT / "settings.gradle.kts"
if settings.is_file():
    source = read(settings)
    for module in ('include(":app")', 'include(":aihub-core")', 'include(":aihub-android")'):
        if module not in source:
            errors.append(f"settings.gradle.kts missing {module}")

workflow = ROOT / ".github" / "workflows" / "core.yml"
if workflow.is_file():
    source = read(workflow)
    if "ubuntu-latest" not in source or ":app:assembleDebug" not in source:
        errors.append("GitHub Actions must build the lightweight Android app on ubuntu-latest")
    if "self-hosted" in source or "chrome_public_apk" in source:
        errors.append("Main Android workflow must not compile full Chromium")

if errors:
    print("AIHub repository validation failed:")
    for error in errors:
        print(" -", error)
    sys.exit(1)

print("AIHub repository validation passed")
print("architecture: aihub-core -> aihub-android -> WebViewBrowserHost -> Android System WebView")
