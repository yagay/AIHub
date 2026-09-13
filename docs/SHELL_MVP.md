# AIHub Shell MVP

AIHub now uses a provider-only shell: one retained Chromium/WebEngine session per AI provider, with no multi-account or workspace management layer.

## Main UI

The shell is organized for fast AI switching rather than browser tab/account management:

- horizontal AI quick-switch rail
- current AI title
- back / forward / reload
- one-tap new chat
- attachment picker
- stop generation
- one shared composer/send control
- custom AI website add button
- tools menu for diagnostics, rule updates and integration token

Switching AI reactivates its retained WebEngine surface whenever possible.

## Session behavior

Each provider owns one deterministic browser container:

```text
profileName   = aihub_provider_<providerId>
persistenceId = aihub_session_<providerId>
```

The user logs in on the real website. AIHub does not store website passwords or duplicate website session data into its own account model.

## Chromium isolation boundary

The shell and core do not import Chromium APIs directly. Direct WebEngine usage is limited to:

```text
WebEngineSessionRuntime.java
AiWebEngineHost.java
```

When Chromium updates, adapt those two files instead of rewriting the AI UI or stable provider/session core.

## External-entry security

`AiHubEntryActivity` is the only exported Activity. `AiHubShellActivity` is intentionally unexported.

Ordinary Android shares and deep links require user confirmation. Unattended custom Intent/Binder actions require the local client token shown under AIHub Tools.

Switch provider:

```bash
adb shell am start \
  -n com.yagay.aihub/com.yagay.aihub.chromium.AiHubEntryActivity \
  -a com.yagay.aihub.action.SWITCH \
  --es provider_id claude \
  --es client_token YOUR_TOKEN
```

Send text to a selected provider:

```bash
adb shell am start \
  -n com.yagay.aihub/com.yagay.aihub.chromium.AiHubEntryActivity \
  -a com.yagay.aihub.action.SEND_TEXT \
  --es provider_id chatgpt \
  --es text "Explain this code" \
  --es client_token YOUR_TOKEN
```

User-confirmed deep link:

```text
aihub://send?provider=gemini&text=hello
```

## Chromium development build

Configure an Android Chromium output directory with at least:

```text
target_os = "android"
```

Then run:

```bash
bash scripts/build_install_aihub.sh /path/to/chromium/src out/Default
```

Repository CI verifies the provider-only core and prevents account/workspace abstractions or Chromium imports from leaking back into stable source. The final WebEngine integration still needs a real Chromium Android checkout and physical-device test.
