# AI Hub architecture

## Hard rules

1. A feature is implemented once in the common core.
2. UI never branches on a provider name.
3. Provider differences are configuration first, provider patch second.
4. Provider switching, account switching and workspace switching all end at `SessionManager.activate()`.
5. Browser/Chromium code stays behind `SessionRuntime`.
6. External callers use `AiCommandBus`; they never receive cookies, tokens or DOM access.
7. A third-party API cannot expose browser profile data.

## Layers

```text
UI / Share / Intent / Binder / Shortcut
                |
            AiCommandBus
                |
          SessionManager
                |
           SessionRuntime
                |
       WebEngineSessionRuntime
                |
        Chromium WebEngine Tab
                |
        Generic DOM action engine
                |
            AI website
```

## Main objects

- `ProviderConfig`: website and capabilities; contains only fallback selectors.
- `AiAccount`: provider login identity and isolated Chromium profile name.
- `AiWorkspace`: maps each provider to a preferred account.
- `AiSessionKey`: `providerId + accountId`.
- `AiSession`: retained conversation/tab state.
- `SessionManager`: the only switching authority.
- `AiCommandBus`: one command entrance for UI and third-party callers.
- `SessionRuntime`: browser implementation boundary.

## Multi-account strategy

Do not rely on Chrome for Android's normal ProfileManager. The Android Chrome UI still has single-regular-profile assumptions. AI Hub should create isolated WebEngine browser profiles and bind each logical account to a `profileName`.

Examples:

```text
ChatGPT / personal -> profileName = personal_gpt
ChatGPT / work     -> profileName = work_gpt
Claude / personal  -> profileName = personal_claude
```

A workspace can map multiple providers to the preferred account:

```text
Personal workspace
  ChatGPT -> gpt_personal
  Claude  -> claude_personal
  Gemini  -> gemini_personal
```

## Switching

All entry points call the same methods:

```text
provider dropdown -> switchProvider()
account dropdown  -> switchAccount()
workspace switch  -> switchWorkspace()
swipe left/right  -> previousProvider()/nextProvider()
external API      -> AiCommandBus.execute()
```

The runtime retains one Tab per session where practical. Switching activates an existing tab instead of reloading the provider home page.

## Unified composer

The generic DOM engine tries semantic discovery before provider selectors:

1. visible textarea
2. `[role=textbox]`
3. `[contenteditable=true]`
4. geometry/position scoring
5. semantic send button (`aria-label`, title, visible text)
6. configured selector fallback
7. provider-specific patch only if all common strategies fail

Keep provider patches small. A provider patch must never contain account/session/UI logic.

## Third-party API

Supported fronts:

- Android Binder/AIDL: preferred for trusted third-party apps because caller UID can be verified.
- Explicit Intents: good for Tasker/MacroDroid/ShortX, but sensitive actions must use an app-managed allowlist/token or user confirmation.
- Android Shares: `ACTION_SEND` / `ACTION_SEND_MULTIPLE` are converted to commands.
- Deep links: only low-risk navigation/switch actions by default.

Never expose cookies, local storage, auth tokens or raw JavaScript execution.
