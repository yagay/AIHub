# AIHub

AIHub is an Android multi-AI browser shell built on Chromium WebEngine. The goal is to keep Chromium's web/runtime capability while replacing the normal browser UI with an interface optimized for switching between AI websites.

Built-in provider rules currently include ChatGPT, Claude, Gemini, Grok and DeepSeek. Custom AI websites can also be added from the app.

## Product direction

AIHub intentionally does **not** manage multiple accounts or workspaces. Each AI provider owns one long-lived Chromium profile/session. The user logs in on the real website, and AIHub simply retains that browser state.

```text
AI-first UI / Share / Intent / Binder / Deep Link
                         ↓
                    AiCommandBus
                         ↓
                   SessionManager
                         ↓
              SessionRuntime interface
                         ↓
             WebEngineSessionRuntime
                         ↓
                 AiWebEngineHost
                         ↓
                 Chromium WebEngine
```

The stable core has no Chromium dependency. Direct `org.chromium.webengine.*` usage is restricted to two integration files:

```text
chromium-overlay/.../WebEngineSessionRuntime.java
chromium-overlay/.../AiWebEngineHost.java
```

When Chromium changes, adapt those integration points instead of rewriting AIHub's provider/session/UI logic.

## AI-first UI

The shell is designed around AI switching rather than normal tab/account management:

- horizontal AI quick-switch rail always visible above the page
- one retained page/session per AI provider
- current AI title in the top browser bar
- browser back / forward / reload retained
- one-tap new chat
- shared attachment control
- shared composer and send button
- stop-generation control
- add custom AI website
- diagnostics/rule tools kept behind the tools menu

Switching AI activates its existing Chromium surface instead of rebuilding or reloading it whenever possible.

## Browser capability policy

AIHub does not fork provider websites or replace their login systems. Website rendering, cookies, local storage, IndexedDB, navigation and browser state remain owned by Chromium/WebEngine.

The long-term rule is: keep browser capabilities in the Chromium integration layer and replace only the shell/UI. Features such as downloads, permissions, camera/microphone, file handling and crash/session restoration should be implemented through the Chromium adapter boundary rather than duplicated in provider-specific code.

## Repository layout

```text
aihub-core/          provider/session/command core; no Android or Chromium dependency
chromium-overlay/    Android AI UI + the small Chromium integration seam
android-api/         provider-only third-party API/AIDL
docs/                architecture, Chromium integration and roadmap
scripts/             validation, Chromium sync/build/install and rule signing
```

Provider differences live in JSON rules, with signed rule bundles available for selector updates without changing the stable core.

## Verify without Chromium

```bash
python3 scripts/validate_provider_rules.py
python3 scripts/validate_repo.py
bash scripts/run_core_smoke_test.sh
```

CI also enforces these architecture rules:

- no account/workspace model may re-enter active source
- direct WebEngine imports may exist only in the two Chromium adapter files
- `AiHubShellActivity` remains unexported
- deep links never carry the reusable client token

## Chromium development build

AIHub is designed to live at `//aihub` inside a Chromium Android checkout. Configure an Android GN output directory first:

```text
target_os = "android"
```

Then run:

```bash
bash scripts/build_install_aihub.sh /path/to/chromium/src out/Default
```

With multiple devices connected:

```bash
bash scripts/build_install_aihub.sh /path/to/chromium/src out/Default DEVICE_SERIAL
```

The flow validates the repository, checks the selected Chromium WebEngine API, syncs AIHub into the checkout, resolves the GN target, builds AIHub plus the local WebEngine support target, installs the APKs and launches the guarded entry Activity.

## Third-party calls

External targeting is provider-only. There is no account/workspace API.

Unattended actions use the local client token in Intent extras or Binder:

```bash
adb shell am start \
  -n com.yagay.aihub/com.yagay.aihub.chromium.AiHubEntryActivity \
  -a com.yagay.aihub.action.SEND_TEXT \
  --es provider_id claude \
  --es text "Explain this code" \
  --es client_token YOUR_TOKEN
```

Deep links are tokenless and require user confirmation:

```text
aihub://send?provider=gemini&text=hello
```

Third-party integrations never receive Chromium cookies, login tokens, localStorage or IndexedDB.

## Signed provider rules

Provider selectors can be updated without changing the stable app core. AIHub accepts only Ed25519-signed rule bundles when a public key is packaged in `chromium-overlay/assets/aihub/rules_public_key.txt`.

```bash
python3 scripts/build_signed_rule_bundle.py \
  --private-key /secure/path/private-key.pem \
  --version 2 \
  --rules chromium-overlay/assets/aihub/providers/*.json \
  --output provider-rules-v2.json
```

## Verification boundary

The provider-only core, rule tooling, repository wiring and architecture invariants are verified by CI. A complete Chromium `autoninja` build and real-device browser test still requires a compatible Chromium Android checkout and device.
