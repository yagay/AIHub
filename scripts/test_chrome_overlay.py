#!/usr/bin/env python3
from __future__ import annotations

import pathlib
import shutil
import subprocess
import sys
import tempfile

ROOT = pathlib.Path(__file__).resolve().parents[1]


def assert_true(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def main() -> None:
    with tempfile.TemporaryDirectory(prefix="aihub-overlay-test-") as raw:
        temp = pathlib.Path(raw)
        chromium = temp / "src"
        aihub = chromium / "aihub"

        (chromium / "chrome" / "android" / "java" / "src" / "org" / "chromium" / "chrome" / "browser").mkdir(
            parents=True
        )
        (chromium / "chrome" / "android").mkdir(parents=True, exist_ok=True)

        (chromium / "chrome" / "android" / "chrome_java_sources.gni").write_text(
            'chrome_java_sources = [\n  "java/src/org/chromium/chrome/browser/Existing.java",\n]\n',
            encoding="utf-8",
        )
        activity = (
            chromium
            / "chrome"
            / "android"
            / "java"
            / "src"
            / "org"
            / "chromium"
            / "chrome"
            / "browser"
            / "ChromeTabbedActivity.java"
        )
        activity.write_text(
            """package org.chromium.chrome.browser;
public class ChromeTabbedActivity {
    @Override
    public void performPostInflationStartup() {
        super.performPostInflationStartup();
        mControlContainer = findViewById(R.id.control_container);
        int keep = 1;
    }

    @Override
    public void anotherMethod() {}
}
""",
            encoding="utf-8",
        )

        shutil.copytree(ROOT, aihub, ignore=shutil.ignore_patterns(".git", ".out", "__pycache__"))
        patcher = aihub / "scripts" / "apply_chrome_overlay.py"

        for _ in range(2):
            subprocess.run([sys.executable, str(patcher), str(chromium)], check=True)

        sources = (chromium / "chrome" / "android" / "chrome_java_sources.gni").read_text(
            encoding="utf-8"
        )
        patched_activity = activity.read_text(encoding="utf-8")
        generated = (
            aihub
            / "generated"
            / "java"
            / "com"
            / "yagay"
            / "aihub"
            / "generated"
            / "AiHubGeneratedRules.java"
        )

        assert_true(sources.count("AIHUB-SOURCES-BEGIN") == 1, "source begin marker duplicated")
        assert_true(sources.count("AIHUB-SOURCES-END") == 1, "source end marker duplicated")
        assert_true(
            sources.count("AiHubChromeBridge.java") == 1,
            "AiHubChromeBridge source duplicated/missing",
        )
        assert_true(
            sources.count("AiHubGeneratedRules.java") == 1,
            "generated provider source duplicated/missing",
        )
        assert_true(
            patched_activity.count("AiHubChromeHook.attach(this)") == 1,
            "ChromeTabbedActivity hook duplicated/missing",
        )
        assert_true(generated.is_file(), "generated provider source missing")
        generated_text = generated.read_text(encoding="utf-8")
        for provider in ("chatgpt", "claude", "gemini", "grok", "deepseek"):
            assert_true(f'\\"id\\":\\"{provider}\\"' in generated_text, f"{provider} not embedded")

    print("AIHub Chrome overlay self-test passed")


if __name__ == "__main__":
    main()
