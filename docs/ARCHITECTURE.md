# AIHub Architecture

The rebuilt `main` branch has one Gradle module (`app`) and several small internal packages. The goal is to keep the codebase lightweight without mixing UI, website DOM details, account storage and WebView lifecycle code.

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
            ↓
        WebViewFactory
            ↓
    Android System WebView
```

Lower layers never depend on the UI.

## Provider reuse

`AiProviderAdapter` is the stable provider contract. The built-in providers all use `GenericWebProviderAdapter`, so they share the same send/new-chat/stop/app-mode implementation.

Provider URL and selector differences live in the single `app/src/main/assets/providers.json` file. Most provider updates and normal new providers therefore require no Java changes.

If a provider later needs behavior that cannot be represented by generic configuration, add a dedicated adapter implementing `AiProviderAdapter`. Do not add provider-name conditionals to `MainController`, `MainScreen`, `WebSessionManager` or `DomBridge`.

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

- Provider URL/selector data belongs in `assets/providers.json`.
- Cross-provider DOM behavior belongs in `DomBridge`.
- Provider exceptions belong in an `AiProviderAdapter` implementation.
- WebView settings belong in `WebViewFactory`.
- Session switching belongs in `WebSessionManager`.
- Account persistence belongs in `AccountRepository`.
- UI layout belongs in `MainScreen`.
- UI/business coordination belongs in `MainController`.
- `MainActivity` stays thin.

Following these boundaries keeps future updates localized and avoids maintaining five separate implementations of the same feature.
