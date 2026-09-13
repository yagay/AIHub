# AIHub

AIHub is a lightweight Android client that uses the official AI websites through **Android System WebView**, while presenting a small shared native Android interface.

It does **not** bundle or compile Chromium. The device WebView runtime provides the browser engine.

Built-in providers:

- ChatGPT
- Claude
- Gemini
- Grok
- DeepSeek

## Design goals

1. One shared implementation, not one app implementation per AI.
2. Provider-specific website details stay behind a small adapter boundary.
3. Provider/account sessions are retained when switching.
4. Multi-account login data is isolated with AndroidX WebKit profiles when supported by the installed WebView runtime.
5. Native UI never knows CSS selectors, cookies or WebView details.
6. Adding or repairing one provider should not require changing the app UI or session engine.

## Architecture

```text
MainActivity
    ↓
MainController
    ├── MainScreen                 native Android UI only
    ├── ProviderRegistry
    │      └── providers.json      URLs/selectors only
    │             ↓
    │      AiProviderAdapter
    │             └── GenericWebProviderAdapter
    │                    └── ProviderSpec
    ├── AccountRepository
    ├── AppPreferences
    └── WebSessionManager
           ├── SessionKey(provider + account)
           ├── WebViewFactory
           └── DomBridge
```

### Stable boundaries

`ui/`
: Native app controls. No website-specific code.

`provider/`
: Uniform AI-provider interface. Normal providers reuse `GenericWebProviderAdapter`.

`assets/providers.json`
: Single configuration source for provider URLs and selector fallbacks.

`model/`
: Small immutable data models such as `ProviderSpec` and `AccountProfile`.

`session/`
: Retains and switches WebViews for each provider/account pair.

`web/`
: All WebView configuration and generic DOM JavaScript live here.

`data/`
: App metadata only. Passwords and website credentials are never stored by AIHub.

## Updating an AI website

The normal maintenance path is intentionally small:

```text
app/src/main/assets/providers.json
        ↓
change URL/selectors for one provider
        ↓
no UI/session/account changes required
```

Adding a normal AI website is also just another entry in `providers.json`.

If a provider eventually needs behavior that cannot be represented by generic selectors, create a provider-specific implementation of `AiProviderAdapter`. The rest of the application stays unchanged.

## APP mode vs WEB mode

AIHub keeps the official website as the service/session layer.

**APP mode** hides common website chrome and the website composer after a usable chat input is detected. AIHub's native controls send commands through the provider adapter.

**WEB mode** shows the normal website, useful for login, verification, settings or features not yet exposed by AIHub's native UI.

Hidden website elements are not removed from the DOM, so the shared command engine can continue to operate the real website controls.

## Multi-account model

Each account is represented by a stable profile id:

```text
ChatGPT / Personal → aihub_chatgpt_<id>
ChatGPT / Work     → aihub_chatgpt_<id>
Claude / Personal → aihub_claude_<id>
```

When `WebViewFeature.MULTI_PROFILE` is supported, `WebViewCompat.setProfile()` assigns each retained WebView its own browsing profile. Cookies, storage and login sessions therefore stay isolated between accounts.

AIHub stores only account labels and profile identifiers in SharedPreferences. Website credentials remain owned by WebView.

## Project structure

The rebuilt project intentionally has only one Gradle Android module:

```text
AIHub/
├── app/
│   └── src/main/
│       ├── assets/providers.json
│       └── java/com/yagay/aihub/
├── build.gradle.kts
├── settings.gradle.kts
└── .github/workflows/core.yml
```

The previous `aihub-core` and `aihub-android` trees were removed from `main`; there is only one active implementation to maintain.

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

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

GitHub Actions builds and lints every push to `main` and uploads the debug APK artifact.
