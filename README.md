# AIHub Chromium Starter

A Chromium/WebEngine-oriented architecture starter for an AI website hub with:

- one unified UI/control path for all AI providers
- very fast provider switching
- multiple accounts with isolated browser profiles
- workspace mappings (Personal / Work / etc.)
- retained sessions
- a generic DOM action engine rather than one full adapter per AI
- a single command bus for app UI and third-party callers
- Binder/AIDL, Intent, Share and deep-link integration points

Initial built-in provider definitions: ChatGPT, Claude, Gemini, Grok and DeepSeek.

## Why WebEngine instead of modifying Chrome Android directly?

The product goal requires multiple simultaneously logged-in website identities and a custom unified UI. Chromium's Android Chrome UI has single-profile assumptions, while WebEngine is the Chromium browser embedding layer intended for custom browser surfaces. Keeping AI Hub on WebEngine also gives us a clean seam between upstream Chromium changes and our application logic.

## Repository layout

```text
aihub-core/          pure Java business/session/command core
chromium-overlay/    only code that touches org.chromium.webengine
android-api/         public third-party API contract/AIDL
docs/                architecture and integration notes
scripts/             local smoke tests
```

## Verify the common core

```bash
./scripts/run_core_smoke_test.sh
```

This test verifies:

- provider + account resolution
- workspace mapping
- switching without provider-specific branches
- command-bus send flow
- previous/next provider switching

## Design constraint

Do not add `if (provider == CHATGPT)` to UI/session/account code. Provider-specific differences belong in rules and, only when unavoidable, a very small provider patch.

## Current status

This archive is the architecture/core starter, not a prebuilt APK. A runnable APK requires integrating the `chromium-overlay` host adapter into a Chromium checkout and building the WebEngine shell/app with Chromium's GN/Ninja toolchain.
