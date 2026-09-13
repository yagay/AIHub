# AIHub

AIHub is a Chromium WebEngine-based Android shell for using multiple AI websites through one shared interface, while keeping provider differences in rules instead of duplicating an app implementation per AI.

Built-in provider rules currently include ChatGPT, Claude, Gemini, Grok and DeepSeek. Custom AI websites can also be added from the app.

## Core design

```text
UI / Share / Intent / Binder / Deep Link
                 ↓
            AiCommandBus
                 ↓
           SessionManager
                 ↓
      WebEngineSessionRuntime
                 ↓
        Chromium WebEngine
                 ↓
     Generic DOM rule engine
```

Provider-specific branches are intentionally kept out of the stable core. Provider differences live in JSON rules, with signed rule bundles available for post-APK selector updates.

## Implemented

- unified provider switcher and previous/next AI switching
- one shared composer, new-chat, stop, back, forward and reload controls
- multiple isolated accounts per AI using separate WebEngine profile names
- Personal/Work-style workspace mappings across providers
- account/workspace add, rename and safe removal flows
- retained session/persistence IDs
- Android file picker and multi-file attachment bridge
- attach-then-send ordering for shares containing both files and prompt text
- custom AI websites
- provider diagnostics and JSON diagnostics export without cookies/tokens/site storage
- JSON provider rules and selector health probing
- Ed25519-signed provider rule bundles with versioning and rollback
- token-gated Intent, deep-link and AIDL/Binder integrations
- Android share confirmation before using a logged-in AI session
- guarded exported `AiHubEntryActivity`; the real `AiHubShellActivity` is unexported
- GitHub Actions validation and core smoke tests

## Repository layout

```text
aihub-core/          provider/account/workspace/session/command core
chromium-overlay/    Android UI + all org.chromium.webengine integration
android-api/         public third-party contract/AIDL
docs/                architecture, build and roadmap notes
scripts/             validation, Chromium sync/build/install and rule signing
```

## Verify without Chromium

```bash
python3 scripts/validate_provider_rules.py
python3 scripts/validate_repo.py
./scripts/run_core_smoke_test.sh
```

GitHub Actions runs the same stable-core checks on every push and pull request.

## Chromium development build

AIHub is designed to live at `//aihub` inside a Chromium Android checkout. Configure an Android GN output directory first, for example:

```text
target_os = "android"
```

Then use the one-command development flow:

```bash
./scripts/build_install_aihub.sh /path/to/chromium/src out/Default
```

With multiple devices connected:

```bash
./scripts/build_install_aihub.sh /path/to/chromium/src out/Default DEVICE_SERIAL
```

The script performs repository validation, WebEngine API compatibility checks, syncs AIHub into the Chromium checkout, resolves the GN target, builds AIHub plus the local WebEngine support target, installs the produced APKs, and launches the guarded entry activity.

Manual build-only and install-only commands are also available:

```bash
./scripts/build_aihub_chromium.sh /path/to/chromium/src out/Default
./scripts/install_aihub_local.sh /path/to/chromium/src out/Default
```

## Third-party calls

Automatic external actions require the local client token shown under AIHub Tools. Ordinary Android shares do not need the token, but require user confirmation before content is sent.

Example:

```bash
adb shell am start \
  -n com.yagay.aihub/com.yagay.aihub.chromium.AiHubEntryActivity \
  -a com.yagay.aihub.action.SEND_TEXT \
  --es text "Explain this code" \
  --es client_token YOUR_TOKEN
```

Third-party integrations never receive WebEngine cookies, login tokens, localStorage or IndexedDB data.

## Signed provider rules

Provider selectors can be updated without changing the stable app core. AIHub accepts only Ed25519-signed rule bundles when a public key has explicitly been packaged in `chromium-overlay/assets/aihub/rules_public_key.txt`.

Create a signed bundle with:

```bash
python3 scripts/build_signed_rule_bundle.py \
  --private-key /secure/path/private-key.pem \
  --version 2 \
  --rules chromium-overlay/assets/aihub/providers/*.json \
  --output provider-rules-v2.json
```

If no public key is configured, signed-rule installation remains disabled rather than falling back to unsigned updates.

## Current verification boundary

The stable core, provider rules, repository wiring and security invariants are continuously verified by CI. A complete Chromium `autoninja` build and on-device WebEngine test still requires a real Chromium Android checkout and device; this repository does not pretend that CI's Java smoke test is a substitute for that integration build.
