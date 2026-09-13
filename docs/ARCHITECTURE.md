# AIHub architecture

## Hard rules

1. AIHub manages providers, not accounts.
2. Each provider owns one retained browser session/profile.
3. UI never branches on a provider name.
4. Provider differences are configuration first; provider patches are a last resort.
5. Browser/Chromium code stays behind `SessionRuntime`.
6. Direct `org.chromium.webengine.*` imports are allowed only in `WebEngineSessionRuntime` and `AiWebEngineHost`.
7. External callers use `AiCommandBus`; they never receive cookies, login tokens or raw DOM/JavaScript access.
8. The real shell is never exported; external requests pass through the guarded entry Activity.

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
                                          ↓
                                  AiWebEngineHost
                                          ↓
                                  Chromium WebEngine
                                          ↓
                               Generic DOM rule engine
                                          ↓
                                      AI website
```

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

The Chromium-facing surface is intentionally concentrated in two files:

```text
WebEngineSessionRuntime.java
AiWebEngineHost.java
```

`WebEngineSessionRuntime` translates stable AIHub operations into browser operations. `AiWebEngineHost` owns the exact upstream WebEngine classes, fragments, tabs and Android file bridge.

A Chromium update should therefore follow this rule:

```text
WebEngine API changed
→ adapt AiWebEngineHost / WebEngineSessionRuntime
→ keep SessionManager, AiCommandBus, provider rules and AI UI unchanged
```

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

The generic DOM engine uses semantic discovery first and provider JSON selectors as fallback. Provider-specific patches must never contain session or UI logic.

## Chromium capability policy

AIHub should preserve browser capability through the Chromium seam instead of reimplementing it per AI. Downloads, permissions, camera/microphone, file chooser behavior, renderer recovery and other browser features belong in the adapter layer.

The shell/UI may be completely AI-specific while the underlying website runtime remains Chromium.

## Third-party API security

- **AIDL/Binder**: unattended operations require the local AIHub client token.
- **Explicit Intents**: provider-only targeting; unattended execution requires the token in Intent extras.
- **Android Shares**: require explicit user confirmation.
- **Deep links**: tokenless and require explicit user confirmation.

Never expose cookies, localStorage, IndexedDB, website auth tokens or raw JavaScript execution to third parties.

## Provider maintenance

Built-in, custom and signed-update providers use the same `ProviderConfig`/JSON rule model. A signed update may override a built-in provider by ID only after Ed25519 verification. The previous signed bundle is retained for rollback.
