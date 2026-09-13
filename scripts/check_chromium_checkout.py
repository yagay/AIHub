#!/usr/bin/env python3
import argparse
import pathlib
import sys

# Keep this synchronized with AiHubChromeBridge.java and apply_chrome_overlay.py. The checker is
# intentionally symbol-based: it should fail early when Chromium moves one of our few hook points.
REQUIRED_FILES = {
    "ChromeTabbedActivity": "chrome/android/java/src/org/chromium/chrome/browser/ChromeTabbedActivity.java",
    "ChromeActivity": "chrome/android/java/src/org/chromium/chrome/browser/app/ChromeActivity.java",
    "Tab": "chrome/browser/tab/java/src/org/chromium/chrome/browser/tab/Tab.java",
    "TabModelSelector": "chrome/browser/tabmodel/android/java/src/org/chromium/chrome/browser/tabmodel/TabModelSelector.java",
    "TabModelUtils": "chrome/browser/tabmodel/android/java/src/org/chromium/chrome/browser/tabmodel/TabModelUtils.java",
    "TabCreator": "chrome/browser/tabmodel/android/java/src/org/chromium/chrome/browser/tabmodel/TabCreator.java",
    "TabClosureParams": "chrome/browser/tabmodel/android/java/src/org/chromium/chrome/browser/tabmodel/TabClosureParams.java",
    "WebContents": "content/public/android/java/src/org/chromium/content_public/browser/WebContents.java",
    "RenderFrameHost": "content/public/android/java/src/org/chromium/content_public/browser/RenderFrameHost.java",
    "IsolatedWorldIds": "content/public/android/java/src/org/chromium/content_public/common/IsolatedWorldIds.java",
    "ChromeJavaSources": "chrome/android/chrome_java_sources.gni",
}

REQUIRED_SYMBOLS = {
    "ChromeTabbedActivity": [
        "performPostInflationStartup()",
        "mControlContainer = findViewById(R.id.control_container);",
    ],
    "ChromeActivity": ["getTabModelSelector(", "getTabCreator("],
    "Tab": [
        "getWebContents(", "getUrl(", "reload(", "stopLoading(", "isLoading(",
        "getProgress(", "canGoBack(", "goBack(", "canGoForward(", "goForward(",
    ],
    "TabModelSelector": [
        "getCurrentTab(", "getTabById(", "selectModel(", "tryCloseTab(",
    ],
    "TabModelUtils": ["runOnTabStateInitialized(", "selectTabById("],
    "TabCreator": ["createNewTab("],
    "TabClosureParams": ["closeTab(", "allowUndo("],
    "WebContents": ["getMainFrame("],
    "RenderFrameHost": ["executeJavaScriptInIsolatedWorld("],
    "IsolatedWorldIds": ["ISOLATED_WORLD_ID_MAX"],
    "ChromeJavaSources": ["chrome_java_sources = ["],
}


def main():
    parser = argparse.ArgumentParser(
        description="Check a Chromium src checkout against AIHub's Chrome Android seam"
    )
    parser.add_argument("chromium_src", help="Path to Chromium src directory")
    args = parser.parse_args()
    root = pathlib.Path(args.chromium_src).resolve()
    errors = []

    if not (root / "BUILD.gn").is_file() or not (root / "chrome" / "android").is_dir():
        errors.append(f"{root} does not look like a Chromium src checkout with //chrome/android")

    texts = {}
    for name, relative in REQUIRED_FILES.items():
        path = root / relative
        if not path.is_file():
            errors.append(f"missing {relative}")
            continue
        texts[name] = path.read_text(encoding="utf-8", errors="replace")

    for name, symbols in REQUIRED_SYMBOLS.items():
        text = texts.get(name, "")
        for symbol in symbols:
            if symbol not in text:
                errors.append(f"{REQUIRED_FILES[name]}: missing expected API/anchor {symbol}")

    # WebLayer/WebEngine was removed from current Chromium and must not become a requirement again.
    if (root / "weblayer" / "public" / "java").exists():
        print("note: checkout still contains legacy //weblayer; AIHub intentionally does not use it")

    if errors:
        print("AIHub Chromium compatibility check FAILED:")
        for error in errors:
            print(" -", error)
        print("\nExpected maintenance boundary:")
        print("  1. chromium-overlay/java/com/yagay/aihub/chromium/AiHubChromeBridge.java")
        print("  2. scripts/apply_chrome_overlay.py (one ChromeTabbedActivity hook + source list)")
        print("  3. scripts/check_chromium_checkout.py")
        print("Keep aihub-core, aihub-android UI/runtime and provider rules revision-independent.")
        sys.exit(1)

    print("AIHub Chromium Chrome-Android compatibility check passed")
    print(f"checkout: {root}")
    print("browser base: //chrome/android:chrome_public_apk")
    print("direct Chromium Java seam: AiHubChromeBridge.java")


if __name__ == "__main__":
    main()
