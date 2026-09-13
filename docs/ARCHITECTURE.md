# AIHub architecture

## Hard rules

1. AIHub manages providers, not accounts.
2. Each provider owns one retained browser session/profile.
3. UI never branches on a provider name.
4. Provider differences are configuration first; provider patches are a last resort.
5. `aihub-core` never depends on Android or Chromium.
6. Direct `org.chromium.webengine.*` imports are allowed only in `AiWebEngineHost.java`.
7. `WebEngineSessionRuntime` is a Chromium-type-free bridge and must remain revision-independent.
8. External callers use `AiCommandBus`; they never receive cookies, login tokens or raw DOM/JavaScript access.
9. The real shell is never exported; external requests pass through the guarded entry Activity.

## Layers

```text
AI-first UI -------------------------------┐
Share / confirmed Deep Link -------------->│
Token-gated Intent / Binder -------------->│
                                          ↓
                                 AiHubEntryActivity
                              (external validation only)
                                          ↓
                                    AiCommandBus
                                          ↓
                                   SessionManager
                                          ↓
                                    SessionRuntime
                                          ↓
                               WebEngineSessionRuntime
                            (no Chromium API types)
                                          ↓
                                  AiWebEngineHost
                              (only direct API seam)
                                          ↓
                                  Chromium WebEngine
                                          ↓
                                      AI website
```

The generic DOM action generator produces high-level scripts before the host boundary; it does not own Chromium objects.

## Stable core

The core is deliberately small:

- `ProviderConfig`: website, capabilities and selector fallbacks.
- `AiSessionKey`: only `providerId`.
- `AiSession`: retained provider session metadata.
- `SessionManager`: one session per provider and all provider switching.
- `AiCommandBus`: common command entrance for UI and external callers.
- `SessionRuntime`: browser implementation boundary.

There is no account registry, account model, workspace model or workspace routing layer.

## Chromium seam

There is one direct Java API adaptation file:

```text
AiWebEngineHost.java
```

`WebEngineSessionRuntime` translates stable AIHub operations into an abstract host interface and derives deterministic provider profile/persistence IDs. It imports no Chromium type.

`AiWebEngineHost` owns the exact upstream WebEngine classes, fragment/tab manager behavior, navigation calls and Android file bridge.

A normal Chromium update should therefore follow this rule:

```text
WebEngine Java API changed
→ adapt AiWebEngineHost.java

GN target/dependency moved
→ adapt chromium-overlay/BUILD.gn

compatibility signature changed
→ adapt scripts/check_chromium_checkout.py

SessionManager / AiCommandBus / provider rules / AI UI
→ unchanged
```

Repository CI rejects any direct WebEngine import outside `AiWebEngineHost.java`.

## Provider session strategy

Each provider gets one deterministic profile and persistence ID:

```text
profileName   = aihub_provider_<providerId>
persistenceId = aihub_session_<providerId>
```

Examples:

```text
ChatGPT  -> aihub_provider_chatgpt
Claude   -> aihub_provider_claude
Gemini   -> aihub_provider_gemini
```

The user logs in directly on each real AI website. Cookies, local storage, IndexedDB and website auth state remain owned by Chromium/WebEngine.

## Active-tab invariant

A provider session owns a `TabManager`, not one permanently cached first `Tab`.

Every normal browser/AI action resolves Chromium's current active tab at operation time. This is required for OAuth, login popups and pages that activate a new tab/window. Attachment upload captures one active tab at the start of that upload so a single transaction cannot be split across pages.

AIHub must not build a parallel popup/tab system unless the selected WebEngine revision proves that an embedder hook is required.

## AI-first switching

The main UI always exposes a horizontal provider rail. Switching provider calls only:

```text
SessionManager.switchProvider(providerId)
```

If that provider has already been opened, its retained browser surface is reactivated rather than intentionally rebuilding the page.

## Unified browser and AI actions

AIHub keeps browser controls and AI controls together:

- back / forward / reload
- provider quick switch
- new chat
- attachment picker
- stop generation
- shared composer/send

The generic DOM engine uses semantic discovery first and provider JSON selectors as fallback. Provider-specific patches must never contain session, browser or UI logic.

## Chromium capability policy

AIHub preserves browser capability through Chromium/WebEngine instead of reimplementing it per AI. Authentication redirects, permissions, camera/microphone, autofill, safe-browsing behavior, downloads, renderer recovery and other browser features belong to Chromium or the single host seam where the selected revision requires embedder code.

The manifest declares common media/location/notification capabilities but does not grant them silently. Actual browser/site permission behavior must be verified against the selected Chromium revision on-device.

See `BROWSER_CAPABILITIES.md` and `INTEGRATION_TEST_CHECKLIST.md`.

## Third-party API security

- **AIDL/Binder**: unattended operations require the local AIHub client token.
- **Explicit Intents**: provider-only targeting; unattended execution requires the token in Intent extras.
- **Android Shares**: require explicit user confirmation.
- **Deep links**: tokenless and require explicit user confirmation.

Never expose cookies, localStorage, IndexedDB, website auth tokens or raw JavaScript execution to third parties.

## Provider maintenance

Built-in, custom and signed-update providers use the same `ProviderConfig`/JSON rule model. A signed update may override a built-in provider by ID only after Ed25519 verification. The previous signed bundle is retained for rollback.
