# Chromium / WebEngine integration

AIHub targets Chromium **WebEngine** while replacing the normal browser shell with an AI-first UI.

The important design rule is that Chromium remains the website/runtime layer, while AIHub owns provider switching, the shared composer and AI-specific navigation UI.

## Upstream verification first

Before debugging AIHub, verify that the selected Chromium revision can build and run its own WebEngine sample.

Typical Android checkout setup:

```bash
fetch --nohooks android
gclient sync
cd src
. build/android/envsetup.sh
gn args out/Default
```

Use Android GN args, including:

```text
target_os = "android"
```

Then verify the upstream WebEngine sample available in that revision.

## One direct Java API seam

Direct `org.chromium.webengine.*` imports are restricted to exactly one AIHub file:

```text
chromium-overlay/java/com/yagay/aihub/chromium/AiWebEngineHost.java
```

`WebEngineSessionRuntime.java` is a stable bridge and intentionally contains no Chromium Java types.

Responsibilities:

```text
WebEngineSessionRuntime
- converts provider/session operations into abstract browser-host operations
- derives deterministic provider profile/persistence IDs
- builds generic DOM actions
- remains independent of a Chromium revision

AiWebEngineHost
- owns WebSandbox / WebFragment / Tab / TabManager usage
- shows/hides provider browser surfaces
- restores provider browser state
- resolves Chromium's current active tab for every browser operation
- bridges Android files into the current page
```

Everything else should remain independent of Chromium API details.

## Upgrade rule

When Chromium updates:

```text
1. run scripts/check_chromium_checkout.py
2. verify the upstream WebEngine sample
3. build AIHub
4. if Java WebEngine API changed, edit AiWebEngineHost.java
5. if GN target names/dependencies changed, edit chromium-overlay/BUILD.gn
6. update check_chromium_checkout.py to match the selected upstream API
7. do not add Chromium-version branches to aihub-core, AI UI or provider rules
```

Repository validation rejects direct WebEngine imports anywhere except `AiWebEngineHost.java`.

## Current WebEngine surface

AIHub's host currently checks/uses a small public surface including:

- `WebSandbox.create()` and fragment creation
- `FragmentParams.setProfileName()`
- `FragmentParams.setPersistenceId()`
- `WebFragment.getTabManager()`
- `TabManager.getActiveTab()` / `createTab()`
- `Tab.executeScript()`
- `Tab.getNavigationController()`
- `NavigationController.navigate()` / `goBack()` / `goForward()` / `reload()`
- `Tab.setActive()` / display-URI access
- the local WebEngine/WebLayer support target

Run:

```bash
python3 scripts/check_chromium_checkout.py /path/to/chromium/src
```

## Provider browser containers

AIHub uses one retained browser container per provider:

```text
profileName   = aihub_provider_<providerId>
persistenceId = aihub_session_<providerId>
```

This is not a multi-account feature. It is only the retained Chromium browser-state container for that AI provider.

## Active-tab rule

Never cache one provider's initial `Tab` and assume it stays correct forever.

OAuth, sign-in flows and website popups can change Chromium's active tab. AIHub therefore stores the provider's `TabManager` and resolves `getActiveTab()` at the time of each send, navigation, diagnostic or URL operation. A file upload captures the active tab once at upload start so one upload transaction cannot be split across two tabs.

This rule keeps AIHub's UI attached to the browser surface Chromium currently considers active without creating a second tab/window system in AIHub.

## Browser capability policy

The goal is to preserve Chromium/WebEngine website capability while using a different shell. AIHub should not duplicate browser subsystems in provider-specific code.

Features such as authentication redirects, permissions, autofill, safe-browsing behavior, camera/microphone, downloads, file chooser behavior, popup/new-window handling, loading state and renderer recovery are treated as **Chromium integration capabilities**. They should be delegated to the selected WebEngine revision where supported and verified on-device before AIHub adds fallback behavior.

See `BROWSER_CAPABILITIES.md` for the verification matrix.

## Build AIHub

```bash
bash scripts/build_aihub_chromium.sh /path/to/chromium/src out/Default
```

Or build + install:

```bash
bash scripts/build_install_aihub.sh /path/to/chromium/src out/Default
```

The scripts validate the repository, check the selected Chromium API surface, sync AIHub to `chromium/src/aihub`, resolve the GN target and build the local target.

## Debugging classification

```text
upstream WebEngine sample fails
→ Chromium/device/environment problem

upstream sample works, compatibility check fails
→ adapt AiWebEngineHost.java / GN wiring / checker

compatibility passes, GN target fails
→ AIHub BUILD.gn/dependency issue

build succeeds, runtime fails
→ capture AIHub diagnostics + logcat and fix host/runtime integration behavior
```

Provider rules, `SessionManager`, `AiCommandBus`, `WebEngineSessionRuntime` and the AI-first UI should not change just because Chromium changed an embedding API.
