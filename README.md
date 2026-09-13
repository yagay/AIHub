# AIHub

AIHub is a lightweight Android client for using official AI websites through **Android System WebView** behind one shared native interface.

It does **not** bundle Chromium and does not require API keys for the built-in web providers.

Built-in providers:

- ChatGPT
- Claude
- Gemini
- Grok
- DeepSeek

Current version: **0.2.0**

## What works

- One native provider switcher for all built-in AIs.
- APP / WEB mode switching.
- Native message composer with send, stop, new-chat, reload and attachment entry points.
- Native conversation mirror in APP mode.
- Full official website in WEB mode for sign-in, verification, settings and unsupported features.
- Multiple accounts per provider using AndroidX WebKit profiles when supported by the installed WebView runtime.
- Retained provider/account WebViews, so switching does not recreate every session.
- Shared file chooser for website uploads.
- Shared DownloadManager integration.
- Shared popup-window handling for website login/navigation flows.
- Text sharing from other Android apps into the AIHub composer.
- Provider configuration validation in CI.
- Debug APK build + Android lint on every latest `main` change.

## Core design

```text
providers.json
      ↓
ProviderRegistry
      ↓
AiProviderAdapter
      ↓
GenericWebProviderAdapter
      ↓
DomBridge
      ↓
WebSessionManager
      ↓
WebViewFactory
      ↓
Android System WebView

MainActivity
      ↓
MainController
      ↓
MainScreen (native UI)
```

The built-in providers reuse the same Java implementation. Provider-specific website details are data in:

```text
app/src/main/assets/providers.json
```

That file contains:

- home URL
- trusted host names
- composer selectors
- send/new-chat/stop selectors
- attachment selectors
- user-message selectors
- assistant-message selectors

For normal website changes, update that file instead of changing the Android UI/session engine.

## APP mode

APP mode does **not** rebuild or remove the website DOM.

The official website remains fully running underneath, while AIHub displays a native conversation surface above it. Native controls invoke the real website controls through the shared provider adapter, and the conversation mirror periodically normalizes rendered website messages into:

```text
ChatMessage(role, text)
```

This keeps the native interface independent from provider-specific HTML structure while avoiding destructive CSS/DOM modifications.

If a website feature is not currently represented in the native UI, tap **APP** to switch to **WEB** and use the normal official page.

## WEB mode

WEB mode exposes the complete official website. Use it for:

- initial sign-in
- account verification
- provider settings
- advanced model/tool selectors
- website-specific features not yet mapped to native controls

Some identity providers may restrict sign-in from embedded WebViews. That is controlled by the website/provider, not AIHub. WEB mode cannot bypass provider authentication policy.

## Multi-account model

AIHub stores only local account labels and stable IDs. Website passwords are never stored by AIHub.

Each provider/account pair receives a stable WebView profile name such as:

```text
aihub_chatgpt_<account-id>
aihub_claude_<account-id>
```

When `WebViewFeature.MULTI_PROFILE` is available, cookies/storage are isolated by the WebView runtime. On runtimes without that capability, AIHub warns that website state may be shared.

## External Android integration

AIHub registers as a `text/plain` Android share target.

Any app can share text to AIHub; the text is placed into the native composer for the user to review and send. AIHub intentionally does not auto-submit arbitrary external text.

## Updating providers

Normal maintenance path:

```text
edit providers.json
        ↓
python3 tools/validate_providers.py
        ↓
CI build + lint
```

The validator catches malformed JSON, duplicate IDs, invalid HTTPS home URLs, bad trusted-host mappings and malformed selector groups.

If a future provider requires behavior that cannot be expressed by selectors, create a dedicated `AiProviderAdapter` implementation. Do not add provider-name conditionals to the UI or session engine.

## Project structure

```text
AIHub/
├── app/
│   └── src/main/
│       ├── assets/providers.json
│       └── java/com/yagay/aihub/
│           ├── app/
│           ├── data/
│           ├── model/
│           ├── provider/
│           ├── session/
│           ├── ui/
│           └── web/
├── tools/validate_providers.py
├── docs/ARCHITECTURE.md
├── build.gradle.kts
├── settings.gradle.kts
└── .github/workflows/core.yml
```

## Build

Requirements:

```text
Java 17
Android SDK 36
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

GitHub Actions validates `providers.json`, builds the debug APK, runs Android lint and uploads the APK artifact.

## Maintenance rule

Keep provider differences in configuration or provider adapters. Keep the rest shared.

The goal is that adding or repairing one AI provider does not require cloning UI, account, WebView, file, download or session code.
