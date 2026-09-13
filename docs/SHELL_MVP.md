# AIHub shell MVP

AIHub's shell is an overlay on the real Chromium `ChromeTabbedActivity`, not a separate browser Activity.

## Main layout

```text
┌──────────────────────────────────────┐
│ ←  →  ↻       Current AI      ＋ Chrome│
├──────────────────────────────────────┤
│ ChatGPT Claude Gemini Grok DeepSeek +AI│
├──────────────────────────────────────┤
│                                      │
│          real Chromium page          │
│                                      │
├──────────────────────────────────────┤
│ ＋   ■   Message current AI…      ➤  │
└──────────────────────────────────────┘
```

The top and bottom bars are created programmatically by `AiHubUiCoordinator`, which has no Chromium imports.

## Provider switching

Each provider maps to a retained normal Chrome tab ID. Selecting a provider asks `AiHubBrowserHost` to select that tab. The Chrome-specific bridge resolves the ID with `TabModelSelector` and activates the normal non-incognito tab model.

No page is intentionally rebuilt just because the user switches AI.

## Chrome controls

AIHub hides the normal Chromium control container by default but keeps it alive. The `Chrome` button toggles those controls back on so the user can reach ordinary browser UI instead of AIHub duplicating it.

## Common composer

The shared composer sends text through the generic DOM engine into the current active page. The UI never asks whether the provider is ChatGPT, Claude, Gemini, Grok or DeepSeek.

Action order:

```text
AIHubUiCoordinator
→ SessionManager
→ BrowserSessionRuntime
→ AiHubBrowserHost
→ current Chrome Tab/main frame
→ GenericDomScriptFactory
→ AI website DOM
```

## Attachments

The `＋` attachment button runs `openAttachmentChooser()` against the active AI page. The script clicks the site's actual file input/control and normal Chromium handles the chooser.

External Android URIs, when an automation/share surface is later attached, use the generic bounded fallback bridge already present in `BrowserSessionRuntime`.

## Custom AI

`＋ AI` opens a simple name/URL form. Custom provider metadata is persisted by `AiHubStateStore`; the website itself remains a normal Chrome tab with normal Chromium site data.

## Provider rules

At a full Chromium build, repository JSON files are generated into `AiHubGeneratedRules.java`. `ProviderRuleLoader` merges them with custom providers and verified signed overrides.

The UI therefore remains generic when provider selectors change.

## Hook boundary

The entire Chrome source integration is one call inserted after the normal control container exists:

```java
com.yagay.aihub.chromium.AiHubChromeHook.attach(this);
```

`AiHubChromeHook` creates `AiHubChromeBridge`, then attaches the stable coordinator.

## Build

```bash
bash scripts/build_aihub_chromium.sh /path/to/chromium/src out/Default
```

Output:

```text
out/Default/apks/ChromePublic.apk
```

There is no separate AIHub application APK in this architecture.
