# AIHub

AIHub is a clean-room Android client that unifies official AI web apps behind one native chat UI.

## Architecture

- **Native UI:** Kotlin + Jetpack Compose + Material 3.
- **Provider/account layer:** one provider can have multiple accounts.
- **Session isolation:** AndroidX WebKit Multi-Profile gives each account independent cookies and web storage when supported by the installed Android System WebView.
- **Provider adapters:** DOM automation is isolated in `app/src/main/assets/providers/`.
- **Official web apps:** authentication, subscriptions and model availability remain on the providers' official websites; web mode does not require provider API keys.

The implementation is clean-room code. Its architecture is inspired by the ideas behind modern Android AI clients, AI Bridge-style account separation, MultAI-style provider adapters, and robust Android WebView wrappers; source code from those projects is not copied into this repository.

## Initial providers

- ChatGPT
- Claude
- Gemini
- Grok
- DeepSeek
- Qwen

## Flow

```text
Compose UI
   ↓
AIHubViewModel
   ↓
Provider + Account + SessionKey
   ↓
WebRuntime (Android WebView)
   ↓
Provider JavaScript adapter
   ↓
Official AI website
```

Tap the globe icon to log into the selected official website. Return to the native chat screen and AIHub injects prompts and reads the latest assistant reply through that provider's adapter.

## Multi-account

Each account maps to a dedicated AndroidX WebKit profile. If `MULTI_PROFILE` is unavailable in the installed WebView implementation, AIHub keeps one default session per provider and refuses creation of extra accounts rather than mixing cookies.

## Build

- JDK 17
- Gradle 9.4.1
- Android Gradle Plugin 9.2.0
- compileSdk / targetSdk 37

```bash
gradle :app:assembleDebug
```

GitHub Actions builds and uploads `AIHub-debug` on every push to `main`.

## Adapter layout

```text
app/src/main/assets/providers/
├── common.js
├── chatgpt.js
├── claude.js
├── gemini.js
├── grok.js
├── deepseek.js
└── qwen.js
```

Website DOMs change frequently. The architecture intentionally keeps selectors outside the Kotlin UI so provider breakage can be repaired by updating a small JavaScript adapter instead of rewriting the app.

## Next layers

The new baseline is ready for richer Markdown, file upload, provider model controls, remote adapter rule updates, conversation export, background execution and an optional local OpenAI-compatible endpoint.
