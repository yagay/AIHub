# AIHub Architecture

AIHub 0.2.0 is a single-module Android application. The project stays lightweight by using Android System WebView as the browser engine and keeping provider differences behind one shared adapter/configuration boundary.

```text
app/src/main/
├── assets/
│   └── providers.json
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
        ├── WebViewFactory.java
        ├── DomBridge.java
        ├── FileChooserCoordinator.java
        └── DownloadHandler.java
```

## Dependency direction

```text
providers.json
      ↓
ProviderRegistry
      ↓
ProviderSpec → AiProviderAdapter → GenericWebProviderAdapter → DomBridge

MainActivity
      ↓
MainController
      ├── MainScreen
      ├── ProviderRegistry
      ├── AccountRepository
      ├── AppPreferences
      └── WebSessionManager
              ├── WebViewFactory
              ├── FileChooserCoordinator
              └── DownloadHandler
                      ↓
              Android System WebView
```

Lower layers never depend on the native UI.

## Provider reuse

`AiProviderAdapter` is the stable provider contract. Built-in providers use `GenericWebProviderAdapter`, so send, new-chat, stop, attachment and conversation-mirroring behavior are shared.

Provider URL/selector differences live only in:

```text
app/src/main/assets/providers.json
```

Normal provider maintenance therefore changes data instead of duplicating Java code.

A provider-specific adapter is allowed only when the provider needs behavior that cannot be represented by shared selectors/semantics. Provider-name branching must not leak into `MainController`, `MainScreen`, `WebSessionManager` or `WebViewFactory`.

## Trusted provider boundary

Every `ProviderSpec` contains trusted host suffixes. Native DOM commands and conversation mirroring are executed only when the current main WebView URL belongs to that provider.

This prevents native AI commands from being injected into unrelated HTTPS pages reached during authentication or normal navigation.

WEB mode remains available for authentication and unsupported website flows.

## APP mode

APP mode is a native overlay architecture, not a destructive webpage transformation.

```text
Native MainScreen conversation surface
                ↑
      ChatMessage(role, text)
                ↑
      WebSessionManager polling
                ↑
       adapter.conversationScript()
                ↑
            DomBridge
                ↑
      official website WebView
```

The official website remains laid out and running underneath the native surface. This is important because website frameworks often depend on visibility, focus, rendering and internal DOM state.

The native composer calls the real website controls through the adapter. The website remains the source of truth for login state, server communication and conversation state.

## WEB mode

WEB mode hides the native conversation/composer surface and exposes the complete retained WebView.

It is the fallback for:

- login and verification
- provider-specific settings
- advanced website tools
- selectors/features not currently mapped to APP mode

APP and WEB use the same retained session; switching modes does not log the user out or create another browser session.

## Conversation mirror

`DomBridge.conversation()` converts configured user/assistant message DOM elements into a provider-neutral JSON array.

`WebSessionManager` periodically evaluates that script only for the visible trusted provider session, parses it into immutable `ChatMessage` records and sends changes to `MainScreen`.

Only the current visible APP session is polled. Background provider/account WebViews are retained but not continuously mirrored.

## WebView reuse

`WebViewFactory` is the single source of truth for:

- JavaScript / DOM storage settings
- cookie behavior
- safe browsing / mixed content policy
- multi-window support
- external-scheme routing
- popup WebViews
- file chooser integration
- downloads
- renderer failure handling callbacks

Changing WebView policy in one place applies to all providers/accounts.

## Multi-account isolation

`AccountRepository` stores account labels and stable local IDs only. It never stores website passwords.

`WebSessionManager` creates one retained session for each:

```text
SessionKey(providerId, accountId)
```

A stable AndroidX WebKit profile name is derived from that pair. When `MULTI_PROFILE` is available, `WebViewCompat.setProfile()` isolates cookies/storage between accounts before the WebView is used.

If the installed WebView runtime does not support multi-profile isolation, AIHub reports the fallback instead of pretending accounts are isolated.

## File and download reuse

`FileChooserCoordinator` owns the single Activity Result file-picker pipeline shared by every WebView.

`DownloadHandler` owns DownloadManager behavior, including user-agent and cookie forwarding. Individual providers contain no file/download Android code.

## Renderer recovery

Each retained session remembers its last completed URL. If the WebView renderer process disappears, `WebSessionManager` recreates only that session WebView and reloads its last URL instead of restarting the whole app.

## External Android integration

`MainActivity` accepts `ACTION_SEND` with `text/plain`. `MainController` places shared text into the native composer.

External input is deliberately not auto-submitted; the user keeps control over which provider/account receives it.

## Configuration validation

`tools/validate_providers.py` runs before Android compilation in CI. It validates:

- JSON structure
- provider ID format and uniqueness
- HTTPS home URLs
- trusted-host ownership
- required selector group types
- non-empty input selectors

The Android registry also validates important constraints at runtime as defense in depth.

## Maintenance rules

- Provider URL/selector data belongs in `assets/providers.json`.
- Cross-provider DOM behavior belongs in `DomBridge`.
- Provider exceptions belong in an `AiProviderAdapter` implementation.
- WebView policy belongs in `WebViewFactory`.
- File picking belongs in `FileChooserCoordinator`.
- Downloads belong in `DownloadHandler`.
- Session retention/synchronization belongs in `WebSessionManager`.
- Account labels/profile IDs belong in `AccountRepository`.
- Native layout belongs in `MainScreen`.
- UI/business coordination belongs in `MainController`.
- `MainActivity` remains a thin lifecycle/intent entry point.

Following these boundaries keeps provider updates localized and avoids maintaining separate Android implementations for each AI website.
