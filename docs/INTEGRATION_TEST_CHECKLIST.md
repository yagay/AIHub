# AIHub Chromium / Android integration test checklist

Use this checklist after the repository-side CI is green and a real Chromium Android checkout/device is available. Do not mark an item complete from the Java smoke test alone.

## 1. Verify the selected Chromium revision

- [ ] `gclient sync` completes successfully.
- [ ] `build/android/envsetup.sh` has been sourced.
- [ ] the GN output contains `target_os = "android"`.
- [ ] Chromium's stock WebEngine sample builds:

```bash
autoninja -C out/Default run_webengine_shell_local
```

- [ ] Chromium's stock WebEngine sample launches on the target device:

```bash
out/Default/bin/run_webengine_shell_local
```

If this stock sample fails, resolve the Chromium/device environment before changing AIHub.

## 2. Build and install AIHub

- [ ] Run the one-command flow:

```bash
bash scripts/build_install_aihub.sh /path/to/chromium/src out/Default DEVICE_SERIAL
```

- [ ] `check_chromium_checkout.py` passes.
- [ ] `gn desc` resolves `//aihub/chromium-overlay:aihub_apk`.
- [ ] `//aihub/chromium-overlay:aihub_local` builds.
- [ ] `AIHub.apk` installs.
- [ ] the local WebEngine support APK installs when the revision produces one.
- [ ] the installer launches `AiHubEntryActivity`, not `AiHubShellActivity`.
- [ ] app starts without renderer/service crashes.

## 3. Built-in provider website/login smoke tests

Test each built-in provider on the selected Chromium revision:

- [ ] ChatGPT opens and accepts normal website login.
- [ ] Claude opens and accepts normal website login.
- [ ] Gemini opens and accepts normal website login.
- [ ] Grok opens and accepts normal website login.
- [ ] DeepSeek opens and accepts normal website login.
- [ ] Google/OAuth flows, when used by a provider website, stay in the real Chromium browsing environment rather than an Android WebView workaround.

Record any provider-specific website behavior as diagnostics/rule data before adding code branches.

## 4. Unified composer/action tests

For every built-in provider:

- [ ] generic input discovery finds the current composer.
- [ ] text insertion fires the website's expected input state.
- [ ] Send works.
- [ ] Enter fallback works where applicable.
- [ ] New chat works or reports a clean rule/probe failure.
- [ ] Stop generation works while an answer is streaming.
- [ ] provider diagnostics report the current URL/input/send/new-chat/stop/file capability state.

If one provider changes its DOM, first update JSON selectors or semantic rules; do not duplicate the whole controller.

## 5. AI switching/session retention

- [ ] log into at least three different AI providers.
- [ ] open an active conversation on each.
- [ ] switch provider repeatedly with the picker.
- [ ] switch with Previous AI / Next AI.
- [ ] switching does not intentionally reload already-open sessions.
- [ ] scroll position/conversation page remains usable after returning.
- [ ] a response that continues while another AI is visible can be revisited safely.
- [ ] back / forward / reload target only the active session.

## 6. Multi-account isolation

For one provider, create at least two accounts:

- [ ] Account A and Account B have different WebEngine profile names.
- [ ] log into different website identities in A and B.
- [ ] switching A → B → A restores the expected login identity each time.
- [ ] cookies/localStorage/IndexedDB do not appear to cross accounts.
- [ ] renaming an AIHub account does not change its profile identity.
- [ ] removing a non-current account repairs workspace mappings.
- [ ] removing the current account closes/replaces its active session cleanly.
- [ ] AIHub prevents removal of the last account for a provider.

Removing an account from AIHub is not considered a verified browser-profile wipe unless the selected WebEngine API provides and passes a supported deletion test.

## 7. Workspace tests

- [ ] create Personal and Work workspaces.
- [ ] map different provider accounts into each workspace.
- [ ] switch workspace and confirm the active provider switches to the workspace-mapped account.
- [ ] switch AI inside a workspace and confirm each provider uses its mapped account.
- [ ] manually choose a different account while a workspace is active and confirm only that provider mapping changes.
- [ ] rename workspace.
- [ ] delete inactive workspace.
- [ ] delete active workspace and confirm AIHub returns to non-workspace account mode without a stale ID.
- [ ] stale/wrong-provider mapping falls back instead of crashing.

## 8. File and image attachments

- [ ] Android document picker opens from the common Attach button.
- [ ] single image upload reaches the active provider website.
- [ ] PDF/file upload reaches providers that support it.
- [ ] multi-file selection works where the provider supports multiple files.
- [ ] unsupported/no-file-input pages fail cleanly rather than sending the prompt accidentally.
- [ ] the 64 MiB aggregate AIHub bridge limit is enforced.
- [ ] Android share of file only attaches the file.
- [ ] Android share of file + text waits for successful attachment injection before sending text.

## 9. External-entry security tests

### Activity exposure

- [ ] `AiHubEntryActivity` is exported.
- [ ] `AiHubShellActivity` is not exported.
- [ ] explicit external launch of `AiHubShellActivity` is rejected by Android.

### Android shares

- [ ] text share displays confirmation.
- [ ] file share displays confirmation.
- [ ] Cancel performs no AI action.
- [ ] Confirm forwards exactly once.

### Deep links

- [ ] `aihub://send?provider=gemini&text=hello` displays confirmation.
- [ ] deep-link URL contains no client token.
- [ ] Cancel performs no action.
- [ ] Confirm performs exactly one action.
- [ ] malformed/unknown commands are rejected.

### Token-gated custom Intent

- [ ] correct client token allows unattended action.
- [ ] missing token is rejected.
- [ ] incorrect token is rejected.
- [ ] rotating the token immediately invalidates old integrations.
- [ ] Activity recreation does not replay an already-consumed internal dispatch.
- [ ] repeated `singleTop` requests are revalidated through `onNewIntent`.

### Binder

- [ ] bind succeeds for a third-party test client.
- [ ] wrong token cannot query provider/account/session data.
- [ ] correct token can list providers/accounts/session state.
- [ ] correct token can switch/send/new/stop/attach/navigate.
- [ ] Binder never returns cookies, website auth tokens, localStorage or IndexedDB.

## 10. Diagnostics and provider rules

- [ ] Provider diagnostics can be viewed/copied.
- [ ] diagnostics JSON exports through Android document creation.
- [ ] exported diagnostics contain no AIHub client token.
- [ ] exported diagnostics contain no cookies/auth/session secrets.
- [ ] custom AI website can be added and receives a default isolated account/profile.
- [ ] invalid custom URL is rejected.

## 11. Signed provider-rule update tests

Before production, create a real offline Ed25519 signing key. Never commit the private key.

- [ ] export only the public key into `chromium-overlay/assets/aihub/rules_public_key.txt`.
- [ ] build a signed bundle with `scripts/build_signed_rule_bundle.py`.
- [ ] valid signature installs.
- [ ] invalid signature is rejected.
- [ ] tampered payload is rejected.
- [ ] same/older bundle version is rejected.
- [ ] newer bundle overrides matching provider IDs.
- [ ] rollback restores the previous valid signed bundle.
- [ ] changing signing public key does not let an unverified old bundle block recovery with a new valid bundle.
- [ ] empty public-key asset disables signed-rule installation rather than accepting unsigned content.

## 12. Lifecycle/recovery tests

- [ ] rotate device while browsing; external commands are not replayed.
- [ ] background/foreground active and inactive providers.
- [ ] swipe AIHub away and reopen; selected provider/account/workspace metadata restores safely.
- [ ] terminate app process and reopen; persisted WebEngine tabs/login state behave as expected for this Chromium revision.
- [ ] renderer/service crash produces recoverable behavior or actionable logs.
- [ ] memory pressure with several AI accounts/providers does not make switching unusable.

## 13. Capture integration result

For the first successful real build, record:

```text
Chromium git revision:
Android version/device:
GN args:
AIHub commit:
WebEngine support APK/package:
Built APK path:
Providers tested:
Known provider rule failures:
Known WebEngine/runtime failures:
```

Only after sections 1–12 have been exercised should the repository describe a specific Chromium revision/device combination as integration-tested.
