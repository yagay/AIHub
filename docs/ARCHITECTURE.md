# AIHub architecture

## Hard rules

1. A feature is implemented once in the common core.
2. UI never branches on a provider name.
3. Provider differences are configuration first; provider patches are a last resort.
4. Provider switching, account switching and workspace switching all end at `SessionManager`.
5. Browser/Chromium code stays behind `SessionRuntime`.
6. External callers use `AiCommandBus`; they never receive cookies, login tokens or raw DOM/JavaScript access.
7. A third-party API cannot expose browser profile data.
8. The real WebEngine shell is never exported; external requests pass through the guarded entry layer.

## Layers

```text
UI ---------------------------------------┐
Share / confirmed Deep Link ------------>│
Token-gated Intent / Binder ------------>│
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
                               Chromium WebEngine Tab
                                         ↓
                               Generic DOM action engine
                                         ↓
                                     AI website
```

In-app UI actions may call the shared core directly. External Android entry points are guarded before they can reach the unexported WebEngine shell.

## Main objects

- `ProviderConfig`: website and capabilities; contains only fallback selectors.
- `AiAccount`: provider login identity and isolated Chromium profile name.
- `AiWorkspace`: maps each provider to a preferred account.
- `AiSessionKey`: `providerId + accountId`.
- `AiSession`: retained conversation/tab state.
- `SessionManager`: switching/account/workspace authority.
- `AiCommandBus`: one command entrance for UI and third-party commands.
- `SessionRuntime`: browser implementation boundary.
- `AiHubEntryActivity`: the only exported Activity; validates/authorizes external requests.
- `AiHubShellActivity`: unexported unified browser UI.

## Multi-account strategy

Do not rely on Chrome for Android's normal ProfileManager. AIHub binds every logical account to a separate WebEngine `profileName` and persistence ID.

Examples:

```text
ChatGPT / personal -> profileName = personal_gpt
ChatGPT / work     -> profileName = work_gpt
Claude / personal  -> profileName = personal_claude
```

A workspace maps multiple providers to preferred accounts:

```text
Personal workspace
  ChatGPT -> gpt_personal
  Claude  -> claude_personal
  Gemini  -> gemini_personal
```

When an account disappears or a stored workspace mapping is stale/wrong-provider, `SessionManager` falls back to a valid account rather than leaving an invalid session key.

## Switching

All switching paths share the same manager:

```text
provider picker   -> switchProvider()
account picker    -> switchAccount()
workspace switch  -> switchWorkspace()
previous/next AI  -> previousProvider()/nextProvider()
external command  -> AiCommandBus.execute()
```

The runtime retains session Tabs where practical. Switching activates an existing session rather than intentionally reloading the provider home page.

## Unified composer and actions

The generic DOM engine tries semantic discovery before provider selectors:

1. visible textarea
2. `[role=textbox]`
3. `[contenteditable=true]`
4. geometry/position scoring
5. semantic action button (`aria-label`, title, visible text)
6. configured selector fallback
7. provider-specific patch only if all common strategies fail

Attachments use the Android document picker and a common WebEngine bridge. `ATTACH_AND_SEND` waits for successful attachment injection before sending prompt text.

Keep provider patches small. A provider patch must never contain account/session/UI logic.

## Third-party API security

- **AIDL/Binder**: unattended operations require the local AIHub client token. A future per-caller package/signature permission layer may be added, but is not claimed as implemented today.
- **Explicit custom Intents**: intended for Tasker/MacroDroid/ShortX and require the client token in Intent extras for unattended execution.
- **Android Shares**: `ACTION_SEND` / `ACTION_SEND_MULTIPLE` are converted to common commands, but require explicit user confirmation.
- **Deep links**: intentionally contain no reusable client token and require explicit user confirmation before execution.

The client token must never be placed in a URL. Never expose cookies, localStorage, IndexedDB, website auth tokens or raw JavaScript execution to third parties.

## Provider maintenance

Built-in, custom and signed-update providers use the same `ProviderConfig`/JSON rule model. A signed update may override a built-in provider by ID, but only after Ed25519 verification. The previous signed bundle is retained for rollback. If no production public key is configured, signed-rule installation stays disabled.
