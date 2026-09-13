# AIHub Chromium Android integration checklist

Use this only after repository CI is green and a real Chromium Android checkout/device is available. A Java/core smoke test is not evidence that a Chromium revision is integration-tested.

## 1. Prepare Chromium

- [ ] `gclient sync` completes.
- [ ] Chromium Android environment is configured.
- [ ] the GN output contains `target_os = "android"`.
- [ ] plain Chromium `chrome_public_apk` builds before applying AIHub, or any upstream build failure is understood.

## 2. Verify the AIHub seam

Run before modifying the checkout:

```bash
python3 scripts/check_chromium_checkout.py /path/to/chromium/src
```

- [ ] checker finds `ChromeTabbedActivity.performPostInflationStartup()`.
- [ ] checker finds the control-container hook anchor.
- [ ] checker finds current tab/model/content APIs.
- [ ] checker finds isolated-world execution API.
- [ ] checker finds `chrome_java_sources.gni`.

## 3. Apply/build

```bash
bash scripts/build_aihub_chromium.sh /path/to/chromium/src out/Default
```

- [ ] AIHub copies to `//aihub`.
- [ ] `AiHubGeneratedRules.java` is generated from provider JSON.
- [ ] `chrome_java_sources.gni` contains one AIHub marker block.
- [ ] `ChromeTabbedActivity.java` contains exactly one AIHub hook call.
- [ ] rerunning the overlay is idempotent.
- [ ] `chrome_public_apk` compiles.
- [ ] `out/Default/apks/ChromePublic.apk` exists.

## 4. Install/launch

```bash
bash scripts/build_install_aihub.sh /path/to/chromium/src out/Default DEVICE_SERIAL
```

- [ ] Chromium's generated runner installs successfully.
- [ ] browser launches normally.
- [ ] no startup crash occurs before/after AIHub overlay attachment.
- [ ] normal page content is visible between the AIHub top/bottom overlays.

## 5. AI-first UI

- [ ] back / forward / reload buttons work.
- [ ] current AI title is visible.
- [ ] built-in provider rail contains ChatGPT, Claude, Gemini, Grok and DeepSeek.
- [ ] shared composer sends text to the current AI website.
- [ ] New Chat works when the current rule/semantic resolver finds it.
- [ ] Stop works while generation is active when supported by the site.
- [ ] Attach opens the website/Chrome native file chooser.
- [ ] `+ AI` can add a custom website.
- [ ] custom provider remains available after app restart.
- [ ] `Chrome` button restores Chromium's normal control container.
- [ ] toggling back to AI-first view does not recreate/reload the page unnecessarily.

## 6. Provider tab retention

For at least three providers:

- [ ] sign in normally.
- [ ] open a conversation/page on each.
- [ ] switch AI repeatedly.
- [ ] each provider returns to its retained real Chrome tab.
- [ ] scroll/page state remains usable.
- [ ] closing a provider tab outside AIHub is handled cleanly on the next open.
- [ ] a provider whose stored tab disappeared receives a new normal tab.
- [ ] provider mapping never reuses an incognito tab.

## 7. Login / OAuth / popup behavior

For providers that support multiple login methods:

- [ ] normal email/password login works.
- [ ] Google/OAuth login flow works through normal Chromium navigation.
- [ ] OAuth flow that opens/switches a tab does not leave subsequent AIHub actions targeting an old cached tab.
- [ ] popup/new-tab login UI behaves as plain Chromium would.
- [ ] after login, provider switching returns to the expected authenticated site.

## 8. Chromium browser capability regression tests

AIHub must not replace these systems; verify the overlay does not break them:

- [ ] download a normal test file and confirm Chromium's download UI/path works.
- [ ] website file chooser works from an AI attachment control.
- [ ] camera permission prompt works on a suitable test page/provider feature.
- [ ] microphone permission prompt works.
- [ ] geolocation permission/site setting works if tested.
- [ ] password/autofill UI remains functional.
- [ ] media playback works.
- [ ] long-press/context/page UI still works when normal Chrome controls are shown.
- [ ] renderer/page crash recovery remains Chromium-native.

## 9. Generic DOM engine

For every built-in provider:

- [ ] input discovery finds the current composer.
- [ ] text insertion updates the website framework state.
- [ ] send-button semantic discovery works, or Enter fallback works.
- [ ] New Chat works via semantic discovery or JSON selector fallback.
- [ ] Stop works via semantic discovery or JSON selector fallback.
- [ ] provider probe returns the current URL/title/action availability.

When one site changes DOM:

```text
semantic resolver
→ provider JSON selectors
→ only then consider a narrowly scoped Java change
```

Do not create a full per-provider controller.

## 10. Attachments and Android share fallback

### Normal AIHub Attach

- [ ] clicking Attach reaches the real website file control.
- [ ] Chromium native picker opens.
- [ ] image upload works on at least one provider.
- [ ] document/PDF upload works where the site supports it.

### Preselected Android URIs

When wired to an external-share surface in a future revision:

- [ ] one URI injects successfully.
- [ ] multiple URIs inject successfully when the provider accepts multiple files.
- [ ] attach-and-send waits for successful injection before sending text.
- [ ] missing file input fails without sending the prompt.
- [ ] the 64 MiB aggregate bridge limit is enforced.

## 11. Provider configuration

- [ ] generated rules exactly represent repository JSON provider IDs/URLs/capabilities.
- [ ] malformed provider JSON fails before Chromium compilation.
- [ ] custom AI accepts http/https URLs only.
- [ ] custom provider metadata does not contain website cookies/passwords.
- [ ] if a production signing public key is configured, valid signed rules verify.
- [ ] invalid/tampered signed rules are rejected.
- [ ] older/equal signed versions are rejected.
- [ ] rollback restores the previous valid bundle.
- [ ] empty public key leaves remote-rule installation disabled.

## 12. Lifecycle / memory

- [ ] background and foreground the browser repeatedly.
- [ ] rotate/change configuration if supported by the device setup.
- [ ] switch among all built-in providers under memory pressure.
- [ ] kill/restart the browser process and verify Chromium restore behavior plus AIHub provider metadata.
- [ ] a stale stored provider tab ID is repaired by opening a new provider tab instead of crashing.
- [ ] second Chrome window / `ChromeTabbedActivity2` does not crash from the inherited AIHub hook.

## 13. Chrome UI escape hatch

- [ ] show Chrome controls.
- [ ] address bar/navigation remains usable.
- [ ] Chrome menu remains usable.
- [ ] site settings/download/history entry points available in that Chromium build remain reachable.
- [ ] hide Chrome controls again and continue current AI page without forced reload.

## 14. Record tested revision

For every declared-good integration record:

```text
Chromium git revision:
AIHub commit:
Android device/model:
Android version:
GN args:
Built APK:
Providers tested:
OAuth providers tested:
Downloads/file chooser tested:
Camera/microphone tested:
Known provider-rule issues:
Known Chromium integration issues:
```

Only after these checks should a specific Chromium revision/device combination be called integration-tested.
