# AIHub Architecture

The rebuilt `main` branch has one Gradle module (`app`) and several small internal packages. The goal is to keep the codebase lightweight without mixing UI, website DOM details, account storage and WebView lifecycle code.

```text
app/
└── src/main/java/com/yagay/aihub/
    ├── app/
    │   └── MainActivity.java
    ├── ui/
    │   ├── MainController.java
    │   └── MainScreen.java
    ├── model/
    │   ├── ProviderSpec.java
    │   └── AccountProfile.java
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
        └── DomBridge.java
```

## Dependency direction

```text
MainActivity
    ↓
MainController
    ↓
MainScreen        ProviderRegistry        AccountRepository
                        ↓
                 AiProviderAdapter
                        ↓
              GenericWebProviderAdapter
                        ↓
                    DomBridge

MainController
    ↓
WebSessionManager
    ↓
WebViewFactory
    ↓
Android System WebView
```

Lower layers never depend on the UI.

## Provider reuse

`AiProviderAdapter` is the stable provider contract. The current built-in providers all use `GenericWebProviderAdapter`, so they share the same send/new-chat/stop/app-mode implementation.

Most website changes should require editing only one `ProviderSpec` entry in `ProviderRegistry`.

If a provider later needs special behavior, add a dedicated adapter implementing `AiProviderAdapter`. Do not add provider-name conditionals to `MainController`, `MainScreen` or `WebSessionManager`.

## WebView reuse

`WebViewFactory` is the only place where WebView defaults and security policy are configured. Any future WebView setting change therefore applies to every provider/account session automatically.

`WebSessionManager` retains a WebView for each `SessionKey(providerId, accountId)`. Switching between providers or accounts reuses that session instead of reloading it.

## Multi-account isolation

`AccountRepository` stores account labels and stable IDs only. A stable WebView profile name is derived from provider ID + account ID.

When AndroidX WebKit reports `MULTI_PROFILE` support, `WebViewFactory` calls `WebViewCompat.setProfile()` before normal WebView use. This gives each provider/account combination separate website storage and cookies.

The app never stores website passwords.

## Native APP mode

The official website remains the service/session layer. `DomBridge` hides common website navigation/composer UI only after it detects a usable chat composer. Hidden elements remain in the DOM so native AIHub controls can still invoke the site's real actions.

`WEB` mode removes that transform and exposes the normal website for login, verification, settings and unsupported features.

## Maintenance rules

- Website selectors belong in `ProviderSpec`.
- Cross-provider DOM logic belongs in `DomBridge`.
- Provider exceptions belong in a provider adapter.
- WebView settings belong in `WebViewFactory`.
- Session switching belongs in `WebSessionManager`.
- Account persistence belongs in `AccountRepository`.
- UI layout belongs in `MainScreen`.
- UI/business coordination belongs in `MainController`.
- `MainActivity` should stay thin.

Following these boundaries keeps future updates localized and avoids maintaining five separate implementations of the same feature.
