# AIHub Shell MVP

AIHub now has a unified Android/WebEngine shell source layer with provider switching, multiple isolated accounts, workspaces, shared composer actions, attachments, diagnostics and third-party integrations.

## Included

- one provider switcher for every AI site
- previous/next AI switching through `SessionManager`
- one account model shared by every provider
- isolated WebEngine `profileName` per account
- account rename/removal with safe workspace repair
- workspace mappings across AI providers
- persisted account/workspace/session metadata
- shared composer, new-chat and stop actions
- browser back/forward/reload
- Android system file picker and multi-file uploads
- ordered attach-then-send for shares with both files and prompt text
- custom AI website registration
- provider health diagnostics and JSON export
- signed provider-rule import and rollback
- Intent, deep-link, share and AIDL/Binder entry points through one `AiCommandBus`

## External-entry security

`AiHubEntryActivity` is the only exported Activity. `AiHubShellActivity` is intentionally unexported.

Ordinary Android shares require a user confirmation dialog before the content reaches the logged-in AI session. Automatic custom Intent/deep-link/Binder actions require the local client token shown in AIHub Tools.

Switch provider:

```bash
adb shell am start \
  -n com.yagay.aihub/com.yagay.aihub.chromium.AiHubEntryActivity \
  -a com.yagay.aihub.action.SWITCH \
  --es provider_id claude \
  --es client_token YOUR_TOKEN
```

Send text:

```bash
adb shell am start \
  -n com.yagay.aihub/com.yagay.aihub.chromium.AiHubEntryActivity \
  -a com.yagay.aihub.action.SEND_TEXT \
  --es text "Explain this code" \
  --es client_token YOUR_TOKEN
```

Deep link:

```text
aihub://send?provider=gemini&text=hello&token=YOUR_TOKEN
```

The external API never returns WebEngine cookies, login tokens, localStorage or IndexedDB.

## Multi-account behavior

AIHub stores only account metadata in Android preferences. Website login state remains owned by the corresponding WebEngine profile. Creating another AIHub account therefore creates a separate browser profile name rather than logging the existing website profile out.

Removing an account from AIHub removes its metadata/session/workspace mappings, but does not claim to erase the underlying WebEngine profile data until a supported upstream profile-data deletion API has been verified.

## Chromium development build

Configure an Android Chromium output directory with at least:

```text
target_os = "android"
```

Then run:

```bash
./scripts/build_install_aihub.sh /path/to/chromium/src out/Default
```

For multiple connected devices, pass the serial as the third argument or set `ANDROID_SERIAL`.

The one-command flow validates the repository and provider rules, checks the selected Chromium WebEngine API surface, syncs AIHub to `src/aihub`, validates the GN target, builds AIHub plus local WebEngine support, installs the APKs and launches `AiHubEntryActivity`.

## Remaining integration boundary

Repository CI validates the stable core and wiring, but the actual WebEngine layer must still be compiled and exercised inside a real Chromium Android checkout. Any Chromium-revision-specific API changes should be fixed only under `chromium-overlay/`; `aihub-core/` should remain unchanged.
