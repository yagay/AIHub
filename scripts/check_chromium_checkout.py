#!/usr/bin/env python3
import argparse
import pathlib
import sys

REQUIRED_FILES = {
    "Tab": "weblayer/public/java/org/chromium/webengine/Tab.java",
    "FragmentParams": "weblayer/public/java/org/chromium/webengine/FragmentParams.java",
    "WebSandbox": "weblayer/public/java/org/chromium/webengine/WebSandbox.java",
    "TabManager": "weblayer/public/java/org/chromium/webengine/TabManager.java",
    "WebFragment": "weblayer/public/java/org/chromium/webengine/WebFragment.java",
}

REQUIRED_SYMBOLS = {
    "Tab": ["executeScript(", "getNavigationController(", "setActive(", "getDisplayUri("],
    "FragmentParams": ["setProfileName(", "setPersistenceId("],
    "WebSandbox": ["create(", "createFragment("],
    "TabManager": ["getActiveTab(", "createTab("],
}


def main():
    parser = argparse.ArgumentParser(description="Check a Chromium src checkout against AIHub WebEngine usage")
    parser.add_argument("chromium_src", help="Path to Chromium src directory")
    args = parser.parse_args()
    root = pathlib.Path(args.chromium_src).resolve()
    errors = []

    if not (root / "BUILD.gn").is_file() or not (root / "weblayer").is_dir():
        errors.append(f"{root} does not look like a Chromium src checkout with //weblayer")

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
                errors.append(f"{REQUIRED_FILES[name]}: missing expected API {symbol}")

    public_build = root / "weblayer/public/java/BUILD.gn"
    if public_build.is_file():
        text = public_build.read_text(encoding="utf-8", errors="replace")
        if "webengine_java" not in text:
            errors.append("//weblayer/public/java no longer exposes webengine_java")
    else:
        errors.append("missing weblayer/public/java/BUILD.gn")

    support_build = root / "weblayer/shell/android/BUILD.gn"
    if support_build.is_file():
        text = support_build.read_text(encoding="utf-8", errors="replace")
        if "weblayer_support_apk" not in text:
            errors.append("//weblayer/shell/android no longer exposes weblayer_support_apk")
    else:
        errors.append("missing weblayer/shell/android/BUILD.gn")

    android_rules = root / "build/config/android/rules.gni"
    if not android_rules.is_file():
        errors.append("missing build/config/android/rules.gni")

    if errors:
        print("AIHub Chromium compatibility check FAILED:")
        for error in errors:
            print(" -", error)
        print("\nAdapt only chromium-overlay/ to the selected Chromium revision; keep aihub-core unchanged.")
        sys.exit(1)

    print("AIHub Chromium compatibility check passed")
    print(f"checkout: {root}")
    print("expected target: //aihub/chromium-overlay:aihub_local")


if __name__ == "__main__":
    main()
