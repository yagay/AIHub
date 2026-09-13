# AIHub

AIHub turns the full open-source Chromium Android browser into an AI-first browser without replacing Chromium's browser engine or website features.

Users sign in to the real AI websites. AIHub adds a unified provider switcher, shared composer and generic DOM actions while normal Chromium continues to own tabs, cookies, login, OAuth, downloads, permissions, file chooser, media, popups, password/autofill and browser lifecycle.

Built-in provider rules currently include ChatGPT, Claude, Gemini, Grok and DeepSeek. Custom AI websites can be added from the AIHub rail.

## Architecture

```text
AIHub provider/session core        aihub-core/
             ↓
AIHub Android UI/runtime           aihub-android/
             ↓
AiHubBrowserHost
             ↓
AiHubChromeBridge + Hook           chromium-overlay/
             ↓
ChromeTabbedActivity / real Tabs
             ↓
Full Chromium Android browser      chrome_public_apk
```

There is no second browser Activity, no standalone AIHub APK and no embedded-browser runtime. AIHub is compiled directly into Chromium's normal Chrome Android target.

### Maintenance boundary

Stable AIHub code must not import Chromium internals. Direct Chrome/content imports are isolated to:

```text
chromium-overlay/java/com/yagay/aihub/chromium/AiHubChromeBridge.java
chromium-overlay/java/com/yagay/aihub/chromium/AiHubChromeHook.java
```

`AiHubChromeHook` depends only on `ChromeTabbedActivity`. `AiHubChromeBridge` owns the small upstream surface: real `Tab`, `TabModelSelector`, tab creation/selection/closure and isolated-world JavaScript execution.

When Chromium changes, adapt those files plus the two compatibility/patch scripts instead of rewriting AIHub's provider, session or UI layers.

## AI-first UI

AIHub overlays the normal tabbed browser with:

- back / forward / reload
- current AI title
- horizontal ChatGPT / Claude / Gemini / Grok / DeepSeek switcher
- `+ AI` custom website entry
- one-tap new chat
- shared attachment action
- stop-generation action
- one shared composer/send control
- a **Chrome** button that restores the normal Chromium toolbar when needed

Provider switching selects the retained real Chrome tab for that provider. AIHub does not recreate a private mini-browser per AI.

## Browser capability policy

Normal browser capability stays in Chromium. AIHub does not reimplement:

- website authentication, Google/OAuth or cookies
- downloads
- camera/microphone/geolocation permission plumbing
- password manager/autofill
- popup/new-window behavior
- media playback
- normal tab lifecycle and restore
- Chrome security/network stack

For a normal in-app attachment, AIHub clicks the website's real file input and Chrome's native chooser handles the request. Android share URIs also have a generic fallback bridge for sending already-selected files into an AI page.

## Provider rules

Provider-specific differences remain configuration-driven:

```text
chromium-overlay/assets/aihub/providers/*.json
```

During Chromium integration, `scripts/apply_chrome_overlay.py` converts those JSON rules into a generated Java class in the checkout and adds it to `chrome_java_sources`. This avoids modifying Chromium's resource/asset pipeline while keeping JSON as the source of truth.

Runtime priority is:

```text
built-in JSON rules
    ↓
user-added custom AI websites
    ↓
verified signed rule bundle overrides
```

The Ed25519 public key is read from:

```text
chromium-overlay/assets/aihub/rules_public_key.txt
```

An empty key disables remote signed-rule installation.

## Repository layout

```text
aihub-core/          pure Java provider/session/command core
aihub-android/       stable Android AI UI, state, rule loader and browser-host runtime
chromium-overlay/    provider JSON + exactly two Chromium-specific Java seam files
docs/                architecture and integration guidance
scripts/             validation, overlay, Chromium build/install and rule signing
```

## Repository verification

These checks do not require a Chromium checkout:

```bash
python3 scripts/validate_provider_rules.py
python3 scripts/validate_repo.py
bash scripts/run_core_smoke_test.sh
```

CI enforces that:

- `aihub-core` has no Android or Chromium dependency
- `aihub-android` has no Chromium internal imports
- the Chromium Java seam contains only `AiHubChromeBridge` and `AiHubChromeHook`
- obsolete standalone/browser-embedding files cannot return
- provider rules remain provider-only; account/workspace abstractions cannot re-enter the core
- build/install scripts target the full `chrome_public_apk`

## Build inside Chromium

Use a current Chromium Android checkout and configure an Android GN output directory:

```text
target_os = "android"
```

Then run:

```bash
bash scripts/build_aihub_chromium.sh /path/to/chromium/src out/Default
```

The flow:

```text
validate AIHub
→ verify the selected Chromium Java/hook surface
→ copy AIHub to //aihub
→ generate provider-rule Java constants
→ patch chrome_java_sources.gni
→ add one attach call to ChromeTabbedActivity
→ build chrome_public_apk
```

Expected APK:

```text
out/Default/apks/ChromePublic.apk
```

Build + install + launch:

```bash
bash scripts/build_install_aihub.sh /path/to/chromium/src out/Default
```

For a specific device:

```bash
bash scripts/build_install_aihub.sh /path/to/chromium/src out/Default DEVICE_SERIAL
```

The installer uses Chromium's generated `out/Default/bin/chrome_public_apk` runner, so package/activity details come from the selected Chromium revision rather than being duplicated in AIHub scripts.

## Chromium upgrade flow

Before applying AIHub to a new Chromium revision:

```bash
python3 scripts/check_chromium_checkout.py /path/to/chromium/src
```

If it fails, normally adapt only:

```text
AiHubChromeBridge.java
scripts/apply_chrome_overlay.py
scripts/check_chromium_checkout.py
```

The checker deliberately watches the few upstream APIs and hook anchors AIHub actually uses.

## Current verification boundary

Repository architecture, provider rules, script syntax and the pure Java core are CI-verified. A full `chrome_public_apk` compile and physical-device validation require an actual Chromium Android checkout/device; this repository does not claim a specific Chromium revision as device-tested until that integration checklist has been completed.
