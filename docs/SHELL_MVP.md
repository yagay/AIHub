# AIHub Shell MVP

AIHub uses a provider-only shell: one retained Chromium/WebEngine session per AI provider, with no multi-account or workspace management layer.

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

The provider container retains a Chromium `TabManager`. AIHub resolves Chromium's current active tab at operation time, so authentication popups/new tabs do not leave the shared AI controls permanently bound to the first tab that was opened.

## Chromium isolation boundary

The shell and core do not import Chromium APIs directly. Direct `org.chromium.webengine.*` usage is limited to one file:

```text
AiWebEngineHost.java
```

`WebEngineSessionRuntime.java` is a stable Chromium-type-free bridge. When Chromium updates, the expected changes are limited to the Host, plus `BUILD.gn`/compatibility signatures if upstream target names or APIs moved.

## Browser capability rule

AIHub changes the shell, not the browser engine. Authentication redirects, permission behavior, camera/microphone, autofill, safe-browsing behavior, downloads and renderer recovery should remain Chromium/WebEngine responsibilities wherever the selected revision supports them.

The host manifest declares common browser capabilities but does not silently grant site permissions. Real behavior is verified using `BROWSER_CAPABILITIES.md` and `INTEGRATION_TEST_CHECKLIST.md`.

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

Repository CI verifies the provider-only core, one-file Chromium Java seam, browser-host architecture and security constraints. The final WebEngine integration still requires a real compatible Chromium Android checkout and physical-device test.
