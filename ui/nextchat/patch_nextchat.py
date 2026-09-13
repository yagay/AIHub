#!/usr/bin/env python3
import pathlib
import re
import sys

root = pathlib.Path(sys.argv[1]).resolve()

def edit(rel, fn):
    path = root / rel
    text = path.read_text(encoding="utf-8")
    updated = fn(text)
    if updated == text:
        raise SystemExit(f"patch produced no change: {rel}")
    path.write_text(updated, encoding="utf-8")


def patch_api(text: str) -> str:
    marker = 'import { Ai302Api } from "./platforms/ai302";'
    if marker not in text:
        raise SystemExit("NextChat api.ts import marker changed")
    text = text.replace(marker, marker + '\nimport { AIHubBrowserApi } from "./platforms/aihub-browser";', 1)
    pattern = re.compile(
        r'  constructor\(provider: ModelProvider = ModelProvider\.GPT\) \{.*?\n  \}\n\n  config\(\) \{\}',
        re.S,
    )
    replacement = (
        '  constructor(_provider: ModelProvider = ModelProvider.GPT) {\n'
        '    // AIHub reuses NextChat UI only. All providers are driven by the native Chrome/CDP engine.\n'
        '    this.llm = new AIHubBrowserApi();\n'
        '  }\n\n'
        '  config() {}'
    )
    text, count = pattern.subn(replacement, text, count=1)
    if count != 1:
        raise SystemExit("NextChat ClientApi constructor shape changed")
    return text


def patch_config(text: str) -> str:
    replacements = {
        'enableAutoGenerateTitle: true,': 'enableAutoGenerateTitle: false,',
        'model: "gpt-4o-mini" as ModelType,': 'model: "chatgpt-web" as ModelType,',
        'sendMemory: true,': 'sendMemory: false,',
        'historyMessageCount: 4,': 'historyMessageCount: 20,',
        'compressMessageLengthThreshold: 1000,': 'compressMessageLengthThreshold: 1000000,',
    }
    for old, new in replacements.items():
        if old not in text:
            raise SystemExit(f"NextChat config marker changed: {old}")
        text = text.replace(old, new, 1)
    return text


def patch_next_config(text: str) -> str:
    marker = '  output: mode,'
    if marker not in text:
        raise SystemExit("NextChat next.config output marker changed")
    return text.replace(
        marker,
        marker + '\n  // Android WebView serves the export below /assets/ui; keep chunks relative.\n  assetPrefix: mode === "export" ? "." : undefined,',
        1,
    )

edit("app/client/api.ts", patch_api)
edit("app/store/config.ts", patch_config)
edit("next.config.mjs", patch_next_config)
print("Patched NextChat for AIHub browser mode")
