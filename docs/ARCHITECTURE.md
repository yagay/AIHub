# AIHub Architecture

AIHub 0.3.0 is a single-module Android application built around one shared native UI and one embedded GeckoView engine. Provider-specific website differences are configuration; browser lifecycle, accounts and APP-mode behavior stay reusable.

```text
app/src/main/
├── assets/
│   ├── providers.json
│   └── aihub_bridge/
│       ├── manifest.json
│       └── content.js
└── java/com/yagay/aihub/
    ├── app/
    │   └── MainActivity.java
    ├── ui/
    │   ├── MainController.java
    │   └── MainScreen.java
    ├── model/
    │   ├── ProviderSpec.java
    │   ├── AccountProfile.java
    │   └── ChatMessage.java
    ├── provider/
    │   ├── AiProviderAdapter.java
    │   ├── GenericWebProviderAdapter.java
    │   └── ProviderRegistry.java
    ├── data/
    │   ├── AccountRepository.java
    │   └── AppPreferences.java
    ├── session/
    │   ├── SessionKey.java
    │   └── WebSessionManager.java
    └── web/
        ├── GeckoEngine.java
        └── GeckoFilePicker.java
```

## Dependency direction

```text
providers.json
      ↓
ProviderRegistry
      ↓
ProviderSpec → AiProviderAdapter → GenericWebProviderAdapter
                                       ↓
                               JSON bridge commands
                                       ↓
MainActivity → MainController → WebSessionManager
                    ↓                 ↕
                MainScreen       WebExtension Port
                                      ↕
                                 GeckoSession
                                      ↕
                                  GeckoView
```

The provider layer does not know which web engine transports its commands. It builds provider-neutral JSON operations such as `probe`, `send`, `newChat`, `stop`, `attach` and `sync`.

## Gecko runtime boundary

`GeckoEngine` owns the process-wide `GeckoRuntime` and the built-in AIHub WebExtension.

The runtime is shared. Browser state is not shared blindly: each provider/account session is created with a stable Gecko `contextId`.

The bundled extension is the only layer that directly touches website DOM. Native Java code does not evaluate arbitrary page JavaScript.

## Provider reuse

Built-in providers use `GenericWebProviderAdapter`. The provider contract exposes command objects rather than scripts, so the UI/session code is independent from ChatGPT, Claude, Gemini, Grok and DeepSeek markup.

Normal website differences live in:

```text
app/src/main/assets/providers.json
```

A dedicated provider adapter is only justified if a website requires behavior that cannot be represented by the shared command model.

## Trusted bridge boundary

The WebExtension content script only matches supported AI hosts. `WebSessionManager` adds two additional checks:

1. the native-messaging sender must belong to the exact retained `GeckoSession`;
2. the sender must be the top-level page.

The current URL must also pass `ProviderSpec.ownsUrl()` before native APP commands are accepted.

Authentication pages can therefore render in WEB mode without receiving AIHub chat commands.

## WEB mode is the safe default

Every provider/account opens in WEB mode.

WEB mode is used for:

- sign-in and verification;
- provider settings;
- provider-specific tools;
- unsupported or changed pages;
- recovery when a selector no longer matches.

Navigating to another URL automatically disables APP mode.

## Capability-gated APP mode

Tapping the WEB/APP control does not blindly show the native overlay.

```text
user requests APP
       ↓
trusted top-level bridge connected?
       ↓ yes
send probe command
       ↓
visible configured/fallback composer found?
       ↓ yes
activate native APP surface
```

If any step fails, the app remains in WEB mode and reports why. This prevents login pages or changed provider pages from being hidden behind an unusable native surface.

## Shared DOM bridge

`assets/aihub_bridge/content.js` implements all normal website interaction:

- live composer probing;
- textarea native value setters;
- ProseMirror/contenteditable insertion;
- semantic send fallback;
- new-chat/stop/attachment clicks;
- mutation-driven conversation synchronization;
- user/assistant message normalization and deduplication.

The bridge receives selector arrays from `AiProviderAdapter`; it does not contain provider-name branching.

## Conversation mirror

When APP mode is active, webpage mutations produce a debounced `dirty` signal. Native code requests a `sync` command, and the bridge returns provider-neutral messages:

```text
ChatMessage(role, text)
```

Only the selected APP session is synchronized into `MainScreen`. Retained background sessions stay alive without continuously updating the native UI.

## Multi-account isolation

`AccountRepository` persists account labels and stable IDs only. Website credentials remain inside the browser engine.

Each retained session is keyed by:

```text
SessionKey(providerId, accountId)
```

and receives a stable Gecko context such as:

```text
aihub_chatgpt_<account-id>
```

Gecko uses that `contextId` to partition persistent cookie/storage state.

## Session switching

There is one visible `GeckoView` and multiple retained `GeckoSession` instances.

Before another open session is attached, `WebSessionManager` releases the currently attached session from `GeckoView`, then attaches the selected retained session. This preserves account/provider sessions without recreating the browser surface.

If a selected Gecko content process crashes, only that session is recreated and its last URL is restored.

## Authentication and popups

Authentication runs in the same Gecko session that owns the provider account state. New-window requests initiated by the page are redirected into that session so login state is not moved into an unrelated browser profile.

The app allows storage-access/persistent-storage content permissions required by modern authentication flows. Other web permissions remain denied until explicitly implemented.

External non-web URI schemes are handed to Android.

## File picking

`GeckoFilePicker` owns one Activity Result document-picker pipeline. Gecko file prompts are confirmed with the selected Android `Uri` values.

No provider contains Android file-picker code.

## Configuration validation

`tools/validate_providers.py` runs before Android compilation and validates provider IDs, HTTPS home URLs, trusted hosts and selector structures.

The final Android build then runs `assembleDebug` and `lintDebug` against compile SDK 37.1.

## Maintenance rules

- Provider URL/selector data belongs in `assets/providers.json`.
- Cross-provider DOM behavior belongs in `assets/aihub_bridge/content.js`.
- Provider exceptions belong in an `AiProviderAdapter` implementation.
- Gecko runtime/WebExtension installation belongs in `GeckoEngine`.
- Session retention, trust checks and APP capability state belong in `WebSessionManager`.
- Android file picking belongs in `GeckoFilePicker`.
- Account labels/context IDs belong in `AccountRepository`.
- Native layout belongs in `MainScreen`.
- UI/business coordination belongs in `MainController`.
- `MainActivity` remains a thin lifecycle/intent entry point.

These boundaries are designed so a provider markup change normally changes one configuration file rather than duplicating browser or Android code.
