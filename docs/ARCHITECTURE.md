# Architecture

AIHub deliberately separates AI product logic from the browser engine.

```text
aihub-core
  ProviderConfig / ProviderRegistry
  SessionManager / AiCommandBus
        ↓
aihub-android
  AiHubUiCoordinator
  BrowserSessionRuntime
  GenericDomScriptFactory
  ProviderRuleLoader
        ↓
AiHubBrowserHost
        ↓
app
  MainActivity
  WebViewBrowserHost
        ↓
Android System WebView
```

## Stable layers

`aihub-core` is pure Java. It must not import Android, AndroidX, WebView or Chromium APIs.

`aihub-android` owns the AI-specific Android UI and generic website automation. It may use normal Android UI APIs but must not depend on concrete `WebView` or Chromium-internal classes.

## Browser adapter

`WebViewBrowserHost` is the concrete browser adapter on `main`. It owns all `WebView`, `WebChromeClient`, `WebViewClient`, downloads, permissions, file chooser, popup and renderer-lifecycle behavior.

If a future browser engine is adopted, implement `AiHubBrowserHost` again instead of rewriting provider/session/UI logic.

## Provider sessions

AIHub intentionally has no account/workspace abstraction. Each Provider owns one retained browser page for the current app process. Website login identity remains website/WebView state.

Provider switching changes which retained page is visible; it does not create a separate browser engine profile per AI.

## Provider differences

Provider-specific differences belong in JSON rules under:

```text
aihub-android/src/main/assets/aihub/providers/
```

The generic DOM engine combines semantic discovery with selector fallbacks. Core/session/UI code must not branch on provider names.

## Archived Chromium implementation

The previous full Chromium source-overlay implementation is preserved on Git branch:

```text
archive/chromium-overlay
```

It is intentionally absent from `main` so normal Android builds remain small and fast.
