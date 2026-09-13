# AIHub

AIHub is a lightweight Android client that presents several official AI websites behind one reusable native interface.

It does **not** compile Chromium and does not use API keys for the built-in web providers. Starting with **0.3.0**, AIHub embeds **Mozilla GeckoView** instead of Android System WebView so the app owns a browser-grade engine and is not tied to the device WebView implementation.

Built-in providers:

- ChatGPT
- Claude
- Gemini
- Grok
- DeepSeek

Current version: **0.3.0**

## Why GeckoView

The old 0.2.x Android WebView design proved too fragile for real sign-in flows and provider DOM integration. AIHub 0.3.0 replaces that layer rather than adding provider-specific WebView workarounds.

GeckoView gives AIHub:

- one embedded browser engine for every provider;
- persistent per-account browser contexts;
- browser content integration through a bundled WebExtension;
- shared popup, file-prompt and navigation handling;
- no dependency on the Android System WebView version installed on the device.

## WEB and APP mode

AIHub always starts a provider in **WEB** mode.

WEB mode displays the complete official website and is the correct place for:

- sign-in and verification;
- provider settings;
- model/tool selection not mapped to native controls;
- any page for which AIHub does not currently understand the chat composer.

Tapping **WEB** requests APP mode. AIHub does not switch immediately. The bundled bridge first probes the live top-level page. APP mode is enabled only when a usable chat composer is detected on a trusted provider host.

If the probe fails, AIHub remains in WEB mode instead of covering the website with an empty native surface.

## Native APP mode

APP mode uses one shared native interface for all providers:

```text
Native composer / message list
          ↕
AiProviderAdapter commands
          ↕
GeckoView built-in WebExtension
          ↕
official provider webpage
```

The WebExtension performs shared operations such as:

- capability probing;
- filling the real website composer;
- send/new-chat/stop/attachment actions;
- normalizing rendered user/assistant messages into `ChatMessage(role, text)`.

Provider-specific differences remain data in:

```text
app/src/main/assets/providers.json
```

Normal website changes should therefore require selector updates rather than Android UI/session rewrites.

## Login behavior

AIHub no longer uses Android WebView for provider login. Authentication runs inside the same Gecko session that later owns the chat session, so provider cookies and storage remain in that account context.

Authentication policy is still controlled by each website or identity provider. For example, an identity provider may reject an embedded sign-in method even when the underlying browser engine is capable of rendering it. AIHub does not bypass those policies.

## Multi-account isolation

AIHub stores only local account labels and stable IDs. It never stores website passwords.

Each provider/account pair receives a stable Gecko `contextId`, for example:

```text
aihub_chatgpt_<account-id>
aihub_claude_<account-id>
```

GeckoView partitions cookie/storage state by that context. Switching provider or account retains its `GeckoSession`; the visible `GeckoView` attaches to the selected session.

## Architecture

```text
providers.json
      ↓
ProviderRegistry
      ↓
AiProviderAdapter
      ↓
GenericWebProviderAdapter
      ↓
WebSessionManager
      ├── retained GeckoSession per provider/account
      ├── GeckoFilePicker
      └── GeckoView (one visible surface)
                 ↕
          GeckoEngine
                 ↕
      built-in WebExtension
                 ↕
         official AI website

MainActivity
      ↓
MainController
      ↓
MainScreen
```

The WebExtension is located in:

```text
app/src/main/assets/aihub_bridge/
├── manifest.json
└── content.js
```

## Provider configuration

`providers.json` contains only provider-specific data:

- home URL;
- trusted hosts;
- composer selectors;
- send/new-chat/stop selectors;
- attachment selectors;
- user-message selectors;
- assistant-message selectors.

The shared bridge prefers configured selectors and uses semantic fallbacks where safe. Provider commands are accepted only from a top-level page connected to the matching retained Gecko session, and native actions are allowed only on the configured trusted provider host.

## File upload

Website file prompts use one shared Android document picker (`GeckoFilePicker`). Providers do not contain Android file-picker code.

## External Android integration

AIHub registers as a `text/plain` share target. Text shared from another Android app is placed in the native composer for review; it is never automatically submitted.

## Updating a provider

Normal maintenance path:

```text
edit app/src/main/assets/providers.json
        ↓
python3 tools/validate_providers.py
        ↓
GitHub Actions: assembleDebug + lintDebug
```

If a provider eventually needs behavior that cannot be expressed by the shared commands/selectors, add a dedicated `AiProviderAdapter`. Do not add provider-name conditionals to `MainScreen`, `MainController` or `WebSessionManager`.

## Project structure

```text
AIHub/
├── app/src/main/
│   ├── assets/
│   │   ├── providers.json
│   │   └── aihub_bridge/
│   └── java/com/yagay/aihub/
│       ├── app/
│       ├── data/
│       ├── model/
│       ├── provider/
│       ├── session/
│       ├── ui/
│       └── web/
├── tools/validate_providers.py
├── docs/ARCHITECTURE.md
└── .github/workflows/core.yml
```

## Build

Requirements:

```text
Java 17
Android SDK 37.1 (compile SDK)
Android target SDK 36
Gradle 9.6
AGP 9.4.0
```

Build:

```bash
gradle :app:assembleDebug
```

Lint:

```bash
gradle :app:lintDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Maintenance rule

Keep provider differences in configuration or provider adapters. Keep browser/session/UI infrastructure shared.

The objective is that fixing one provider does not create a second implementation of accounts, navigation, native UI, file selection or browser-session management.
