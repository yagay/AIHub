# AIHub

AIHub is a lightweight Android app for using multiple AI websites through one AI-first interface. The current `main` branch uses **Android System WebView + AndroidX WebKit**, so GitHub Actions builds only the AIHub app instead of compiling Chromium itself.

Built-in sites: **ChatGPT, Claude, Gemini, Grok and DeepSeek**. Custom AI websites can be added from the `＋ AI` button.

## Why System WebView

Android System WebView provides the Chromium-based web runtime on the device and is updated independently from AIHub. AIHub therefore stays small and fast to build while still using real AI websites and their normal web sessions.

The previous full-Chromium overlay implementation is preserved on:

```text
archive/chromium-overlay
```

## Architecture

```text
aihub-core/
Pure Java provider/session/command model
        ↓
aihub-android/
AI-first UI + generic DOM engine + provider rules
        ↓
AiHubBrowserHost
        ↓
app/WebViewBrowserHost
        ↓
Android System WebView
```

`aihub-core` has no Android dependency. `aihub-android` has no concrete WebView or Chromium-internal dependency. All System WebView behavior is isolated in `app/src/main/java/com/yagay/aihub/app/WebViewBrowserHost.java`.

## AI-first UI

The app provides:

- back / forward / reload
- horizontal AI switcher
- retained page per AI, so switching does not reload the previous page
- one shared composer/send action
- new chat and stop-generation actions
- native website file chooser
- custom AI websites
- remembered last selected AI

## Browser capabilities

`WebViewBrowserHost` currently handles:

- JavaScript and DOM storage
- shared website cookies and third-party cookies
- file chooser / upload
- downloads through Android `DownloadManager`
- camera and microphone website permission requests
- geolocation permission requests
- popup/new-window WebViews
- external URL schemes
- renderer-process crash recovery
- Safe Browsing
- mixed-content blocking
- direct `file://` access disabled

Website DOM actions remain generic and configuration-driven rather than containing `if (provider == ChatGPT)` style branches.

## Provider rules

Built-in provider JSON lives in:

```text
aihub-android/src/main/assets/aihub/providers/
```

`index.txt` lists the packaged rules. Runtime priority is:

```text
packaged JSON
→ user-added custom providers
→ verified signed rule overrides
```

Remote signed rules are disabled until a real Ed25519 public key is placed in:

```text
aihub-android/src/main/assets/aihub/rules_public_key.txt
```

## Android support

Current configuration:

```text
minSdk      31  (Android 12)
compileSdk  36
 targetSdk  36
Java        17
AGP         9.4.0
Gradle      9.6.0
AndroidX WebKit 1.17.0
```

The app can run on newer Android versions as well; compile/target SDK can be moved to API 37 when the stable SDK package is available to the build environment.

## Build locally

With Android SDK 36 and Java 17 installed:

```bash
gradle :app:assembleDebug
```

APK:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## GitHub Actions

Every push to `main` builds on ordinary `ubuntu-latest`; no self-hosted Chromium build machine is required.

Workflow:

```text
.github/workflows/core.yml
```

Successful runs upload an artifact named similar to:

```text
AIHub-debug-214
```

containing `app-debug.apk`.

## Repository checks

```bash
python3 scripts/validate_provider_rules.py
python3 scripts/validate_repo.py
bash scripts/run_core_smoke_test.sh
gradle :app:assembleDebug
```

## Compatibility note

System WebView is intentionally much simpler than maintaining a Chromium fork, but it is not identical to full Chrome. Some identity providers—especially Google sign-in flows—may restrict authentication inside embedded WebViews. ChatGPT/Claude/Gemini/Grok/DeepSeek login and upload behavior therefore still need physical-device testing; AIHub does not spoof Chrome or bypass a provider's login policy.
