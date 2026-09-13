# AIHub Chromium / Android integration test checklist

Use this checklist after repository-side CI is green and a real Chromium Android checkout/device is available. Do not mark an item complete from the Java smoke test alone.

## 1. Verify the selected Chromium revision

- [ ] `gclient sync` completes successfully.
- [ ] `build/android/envsetup.sh` has been sourced.
- [ ] the GN output contains `target_os = "android"`.
- [ ] Chromium's stock WebEngine sample builds and launches on the target device.
- [ ] `python3 scripts/check_chromium_checkout.py /path/to/chromium/src` passes.

If the stock WebEngine sample fails, resolve the Chromium/device environment before changing AIHub.

## 2. Build and install AIHub

- [ ] Run:

```bash
bash scripts/build_install_aihub.sh /path/to/chromium/src out/Default DEVICE_SERIAL
```

- [ ] `gn desc` resolves `//aihub/chromium-overlay:aihub_apk`.
- [ ] `//aihub/chromium-overlay:aihub_local` builds.
- [ ] `AIHub.apk` installs.
- [ ] the matching local WebEngine support APK installs when required by the revision.
- [ ] installer launches `AiHubEntryActivity`, not the unexported shell directly.
- [ ] app starts without renderer/service crashes.

## 3. Built-in provider login smoke tests

For ChatGPT, Claude, Gemini, Grok and DeepSeek:

- [ ] website opens normally.
- [ ] normal website login works.
- [ ] login survives switching away and back.
- [ ] login survives process/app restart as supported by the selected WebEngine revision.
- [ ] Google/OAuth redirects remain inside the real Chromium browsing environment.
- [ ] popup/new-tab login flows do not leave AIHub actions attached to a stale tab.

AIHub has one retained Chromium profile/session per provider. There is no multi-account/workspace test matrix.

## 4. Active-tab behavior

For at least one provider that uses an OAuth popup/new tab:

- [ ] start on the provider tab.
- [ ] launch the login popup/new tab.
- [ ] confirm Chromium changes the active tab as expected.
- [ ] AIHub Back/Forward/Reload target the current active tab.
- [ ] AIHub diagnostics report the current active tab URL.
- [ ] after login returns to the provider page, AIHub Send/New chat/Stop operate on the active provider page.
- [ ] attachment upload captures one active tab at upload start and does not split one upload across tabs.

## 5. Unified composer/action tests

For every built-in provider:

- [ ] generic input discovery finds the current composer.
- [ ] text insertion triggers the website's input state.
- [ ] Send works.
- [ ] Enter fallback works where applicable.
- [ ] New chat works or reports a clean rule/probe failure.
- [ ] Stop generation works while an answer is streaming.
- [ ] provider diagnostics report current URL/input/send/new-chat/stop/file capability state.

If one provider changes its DOM, update JSON selectors/semantic rules before adding code branches.

## 6. AI switching/session retention

- [ ] log into at least three providers.
- [ ] open a conversation on each.
- [ ] switch repeatedly using the horizontal AI rail.
- [ ] switch with Previous AI / Next AI.
- [ ] switching does not intentionally reload an already-open provider session.
- [ ] scroll position/conversation page remains usable after returning.
- [ ] background generation can be revisited safely.
- [ ] back / forward / reload target only the visible provider's current active tab.

## 7. File and image attachments

- [ ] Android document picker opens from the common Attach button.
- [ ] single image upload reaches the active provider website.
- [ ] PDF/file upload reaches providers that support it.
- [ ] multi-file selection works where supported.
- [ ] unsupported/no-file-input pages fail cleanly rather than sending the prompt accidentally.
- [ ] the 64 MiB aggregate AIHub bridge limit is enforced.
- [ ] Android share of file only attaches the file.
- [ ] Android share of file + text waits for attachment injection before sending text.

## 8. Browser media / permission capabilities

The host manifest declares browser capability permissions, but the selected WebEngine revision must still be verified to provide the site/browser permission flow.

- [ ] camera permission request is user-visible and denial is respected.
- [ ] microphone permission request is user-visible and denial is respected.
- [ ] provider voice/WebRTC works after permission is granted.
- [ ] location request is user-visible and denial is respected.
- [ ] notification behavior is tested on Android 13+.
- [ ] AIHub never silently grants a website permission.

If WebEngine requires an embedder callback in this revision, implement it in `AiWebEngineHost.java`, not provider code.

## 9. Native Chromium browser behavior

- [ ] autofill/password-manager behavior matches the selected WebEngine revision.
- [ ] safe-browsing behavior remains enabled/usable as provided by WebEngine.
- [ ] external/native intent handling used by login/payment flows behaves correctly.
- [ ] downloads are tested before AIHub claims download support.
- [ ] popup/new-window behavior is tested before adding any AIHub-specific fallback.
- [ ] loading/progress observer API is verified before wiring a custom progress indicator.

Do not implement provider-specific browser subsystems to make this checklist pass.

## 10. External-entry security tests

### Activity exposure

- [ ] `AiHubEntryActivity` is exported.
- [ ] `AiHubShellActivity` is not exported.
- [ ] direct external launch of `AiHubShellActivity` is rejected by Android.

### Android shares

- [ ] text/file shares display confirmation.
- [ ] Cancel performs no AI action.
- [ ] Confirm forwards exactly once.

### Deep links

- [ ] `aihub://send?provider=gemini&text=hello` displays confirmation.
- [ ] URL contains no client token.
- [ ] malformed/unknown commands are rejected.

### Token-gated Intent/Binder

- [ ] correct client token allows unattended action.
- [ ] missing/incorrect token is rejected.
- [ ] rotating the token immediately invalidates old integrations.
- [ ] Activity recreation does not replay consumed dispatch.
- [ ] repeated `singleTop` requests are revalidated through `onNewIntent`.
- [ ] Binder can list providers and query the current provider/session state.
- [ ] Binder can switch/send/new/stop/attach/navigate.
- [ ] Binder never returns cookies, auth tokens, localStorage or IndexedDB.

## 11. Diagnostics, custom providers and signed rules

- [ ] provider diagnostics can be viewed/copied/exported.
- [ ] exported diagnostics contain no client token/cookies/auth/session secrets.
- [ ] custom AI website can be added and gets one deterministic retained provider profile/session.
- [ ] invalid custom URL is rejected.
- [ ] valid Ed25519-signed rule bundle installs.
- [ ] invalid/tampered signature is rejected.
- [ ] same/older version is rejected.
- [ ] rollback restores the previous valid bundle.
- [ ] empty public-key asset disables signed-rule installation instead of accepting unsigned data.

## 12. Lifecycle/recovery tests

- [ ] rotate device while browsing; external commands are not replayed.
- [ ] background/foreground active and inactive providers.
- [ ] swipe AIHub away and reopen; selected provider restores safely.
- [ ] terminate app process and reopen; provider WebEngine state behaves as expected for the revision.
- [ ] renderer/service crash produces recoverable behavior or actionable logs.
- [ ] memory pressure with several provider sessions does not make switching unusable.

## 13. Capture the first verified integration result

Record:

```text
Chromium git revision:
Android version/device:
GN args:
AIHub commit:
WebEngine support APK/package:
Built APK path:
Providers tested:
OAuth/new-tab flows tested:
Camera/microphone/location result:
Download result:
Known provider rule failures:
Known WebEngine/runtime failures:
```

Only after these sections are exercised should a specific Chromium revision/device combination be described as integration-tested.
