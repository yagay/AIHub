# AIHub

AIHub is a fresh Android implementation that uses **Titanium Browser** as the logged-in web runtime and **NextChat** as the chat UI. It does not use OpenAI, Anthropic, Google Gemini, DeepSeek or xAI developer API keys.

## Architecture

`NextChat UI -> 127.0.0.1 IPC broker -> AIHub Titanium extension -> Prometheus Browser-Tab core -> logged-in AI websites`

Supported web providers: ChatGPT, Claude, Gemini, DeepSeek and Grok.

The provider automation core is reused from `fibbersha-hub/prometheus-ai-orchestrator` Browser-Tab mode (Apache-2.0). AIHub intentionally excludes Prometheus API-provider mode. Titanium Browser is external and is not redistributed by AIHub.

Root is used to stage the unpacked extension into Titanium private storage and add Chromium's rooted `chrome-command-line` `--load-extension` switch. No Chromium build, Node.js, Playwright, GeckoView or external server is required.
