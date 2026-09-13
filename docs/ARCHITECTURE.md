# AIHub architecture

## Hard rules

1. AIHub manages providers, not accounts/workspaces.
2. Each provider maps to one retained normal Chrome tab.
3. UI never branches on provider names.
4. Provider differences are JSON/configuration first.
5. The stable core never imports Android or Chromium APIs.
6. The stable Android layer never imports Chromium internals.
7. Direct Chromium Java API use is isolated to `AiHubChromeBridge`; `AiHubChromeHook` may import only `ChromeTabbedActivity`.
8. Chromium remains responsible for browser features. AIHub changes the AI workflow/UI, not the browser engine.
9. A Chromium upgrade must not introduce version branches into `aihub-core` or `aihub-android`.

## Layers

```text
Provider JSON / custom AI
           ↓
      ProviderRegistry
           ↓
      SessionManager
           ↓
      SessionRuntime
           ↓
  BrowserSessionRuntime             aihub-android
           ↓
     AiHubBrowserHost
           ↓
     AiHubChromeBridge              Chromium seam
           ↓
TabModelSelector / Tab / WebContents
           ↓
     Chromium Chrome Android
```

The AI overlay itself is also stable Android code:

```text
AiHubUiCoordinator
  ├ provider rail
  ├ custom AI entry
  ├ common composer
  ├ new chat / stop / attach
  ├ browser back/forward/reload
  └ Chrome-controls toggle
```

## Stable core

`aihub-core` contains:

- `ProviderConfig`
- `ProviderRegistry`
- `AiSessionKey`
- `AiSession`
- `SessionManager`
- `AiCommandBus`
- `SessionRuntime`

It is plain Java. There is no Android, Chromium, account, workspace or website-login implementation in the core.

## Stable Android layer

`aihub-android` contains everything that is Android-specific but not Chromium-version-specific:

- `AiHubBrowserHost`
- `BrowserSessionRuntime`
- `AiHubUiCoordinator`
- generic DOM actions
- custom provider creation
- provider JSON codec/loader
- AIHub metadata persistence
- signed provider rule verification

This layer may use standard Android APIs but must not import `org.chromium.chrome.*`, `org.chromium.content_public.*` or old browser-embedding APIs.

## Chromium seam

Exactly two Java files live in the active Chromium-specific package:

```text
AiHubChromeBridge.java
AiHubChromeHook.java
```

### AiHubChromeBridge

Owns current Chromium API calls:

- `ChromeTabbedActivity`
- `TabModelSelector`
- `TabModelUtils`
- normal `TabCreator`
- `Tab` navigation/loading state
- provider → real Chrome tab-id mapping
- `WebContents.getMainFrame()`
- `RenderFrameHost.executeJavaScriptInIsolatedWorld()`

AIHub provider actions always resolve against the current real Chrome browsing environment rather than a second embedded browser.

### AiHubChromeHook

The only purpose of the hook is to construct the bridge and attach the stable coordinator. Its Chromium dependency must remain only `ChromeTabbedActivity`.

## Overlay patch

`scripts/apply_chrome_overlay.py` performs only three build-time mutations:

```text
1. generate AiHubGeneratedRules.java from provider JSON/public key
2. add AIHub Java sources to chrome_java_sources.gni
3. insert AiHubChromeHook.attach(this) after ChromeTabbedActivity creates control_container
```

No large Chromium source fork is copied into this repository.

## Provider tab strategy

AIHub stores only the normal Chrome tab ID associated with each provider ID:

```text
chatgpt  -> tab 123
claude   -> tab 126
gemini   -> tab 131
```

When switching AI:

```text
provider id
→ retained tab id
→ TabModelSelector.getTabById()
→ select normal model
→ select that real tab
```

If the tab no longer exists, opening the provider creates a normal foreground Chrome tab and stores the new ID.

Website cookies/login state remain normal Chromium state. AIHub does not duplicate or export them.

## DOM action model

The shared composer does not maintain provider-specific controllers. `GenericDomScriptFactory` uses semantic discovery first and JSON selectors as fallback.

The current tab's live main frame executes the generic script in an isolated world. This keeps AIHub code outside the page's normal JavaScript world while still operating on the same DOM.

Provider-specific differences belong in JSON selectors. A provider-specific Java implementation is a last resort and should not contain session/UI/browser ownership.

## Attachments

Two paths are intentionally different:

```text
AIHub attach button
→ click website's real input[type=file]
→ Chrome native file chooser

Android external share URI
→ AIHub ContentResolver bridge
→ generic DataTransfer/file-input injection
```

The first path preserves Chrome's normal file chooser behavior. The second exists only because the file URI has already been selected by another Android app.

## Browser capability ownership

Chromium owns:

- cookies/storage/login/OAuth
- permissions and site settings
- downloads
- media
- popup/new-window behavior
- password manager/autofill
- network/security stack
- tab restore/lifecycle

AIHub owns:

- provider list
- provider-to-tab association
- AI switching UI
- common composer/actions
- provider rules
- custom AI metadata

## Upgrade rule

For a new Chromium revision:

```text
scripts/check_chromium_checkout.py
        ↓
if API/anchor moved
        ↓
AiHubChromeBridge.java
apply_chrome_overlay.py
check_chromium_checkout.py
        ↓
aihub-core and aihub-android stay unchanged
```

That separation is the primary maintenance invariant of the project.
