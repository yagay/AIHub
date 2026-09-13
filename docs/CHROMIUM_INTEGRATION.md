# Chromium / WebEngine integration

AIHub targets Chromium **WebEngine** while replacing the normal browser shell with an AI-first UI.

The important design rule is that Chromium remains the website/runtime layer, while AIHub owns provider switching, the composer and AI-specific navigation UI.

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

## Two-file integration seam

Direct `org.chromium.webengine.*` imports are restricted to exactly two AIHub files:

```text
chromium-overlay/java/com/yagay/aihub/chromium/WebEngineSessionRuntime.java
chromium-overlay/java/com/yagay/aihub/chromium/AiWebEngineHost.java
```

Responsibilities:

```text
WebEngineSessionRuntime
- converts provider/session operations into browser operations
- derives deterministic provider profile/persistence IDs
- executes provider DOM actions
- exposes current URL / probe helpers

AiWebEngineHost
- owns WebSandbox / WebFragment / Tab / TabManager usage
- shows/hides provider browser surfaces
- restores retained tabs
- bridges Android files into the page
```

Everything else should remain independent of Chromium API details.

## Upgrade rule

When Chromium updates:

```text
1. run scripts/check_chromium_checkout.py
2. verify the upstream WebEngine sample
3. build AIHub
4. if WebEngine API changed, edit only AiWebEngineHost/WebEngineSessionRuntime
5. do not add Chromium-version branches to aihub-core
```

Repository validation rejects new direct WebEngine imports outside those two files.

## Current WebEngine surface

AIHub currently depends on a small public surface including:

- `WebSandbox.create()` and fragment creation
- `FragmentParams.setProfileName()`
- `FragmentParams.setPersistenceId()`
- `TabManager.getActiveTab()` / `createTab()`
- `Tab.executeScript()`
- `Tab.getNavigationController()`
- active-tab and display-URI access
- local WebEngine/WebLayer support target

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

This is not a multi-account feature. It is only the browser-state container for that AI provider.

## Browser capability policy

The goal is to preserve Chromium website/browser capability while using a different UI. Browser features belong in the integration seam, not in provider-specific code.

Examples to add/verify after the first real build:

- downloads
- permission prompts
- camera/microphone
- popup/new-window login flows
- file chooser behavior
- renderer crash recovery
- loading/progress state

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
→ adapt the two Chromium seam files / GN wiring

compatibility passes, GN target fails
→ AIHub BUILD.gn/dependency issue

build succeeds, runtime fails
→ capture AIHub diagnostics + logcat and fix the seam/runtime behavior
```

Provider rules, `SessionManager`, `AiCommandBus` and the AI-first UI should not change just because Chromium changed an embedding API.
