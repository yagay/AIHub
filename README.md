# AIHub

AIHub is a fresh Android implementation that uses **Titanium Browser** (`io.github.jqssun.helium`) as the logged-in Chromium runtime and **NextChat** as the chat UI. It does not use OpenAI, Anthropic, Google Gemini, DeepSeek or xAI developer API keys.

## Architecture

`NextChat UI -> loopback broker -> WebSocket -> AIHub Titanium companion extension -> Prometheus Browser-Tab core -> logged-in AI websites`

Supported web providers: ChatGPT, Claude, Gemini, DeepSeek and Grok.

The provider automation core is reused from `fibbersha-hub/prometheus-ai-orchestrator` Browser-Tab mode (Apache-2.0). AIHub intentionally excludes Prometheus API-provider mode. Titanium Browser is external and is not redistributed by AIHub.

## Titanium integration

- Root stages the generated companion extension inside Titanium private storage.
- Extension updates are content-hash based; unchanged files are not recopied.
- The APK is also an LSPosed API 102 module scoped only to `io.github.jqssun.helium`.
- When LSPosed is enabled, AIHub hooks Chromium `CommandLine` startup and injects the unpacked extension path on every Titanium process start.
- Before LSPosed is enabled, AIHub has a Root fallback: it temporarily writes the Chromium command line only while launching Titanium, then immediately restores the previous file so other Chromium browsers are not left modified.

No Chromium build, GeckoView, Chrome CDP, Node.js, Playwright, token-free-gateway or external server is part of this branch.
