# Chromium Chrome Android integration

AIHub integrates with the full Chromium Android browser target, not a second embedded browser runtime.

The output is the normal development browser APK:

```text
//chrome/android:chrome_public_apk
out/Default/apks/ChromePublic.apk
```

## Why this integration model

AIHub's goal is to replace/augment the browser UI for AI workflows while preserving Chromium's own website/browser behavior. Building directly into Chrome means login, cookies, OAuth, downloads, permissions, password/autofill, popups, media and normal tab handling stay on Chromium's normal code paths.

## Current upstream seam

The current AIHub bridge depends on a deliberately small Android Java surface:

```text
ChromeTabbedActivity
ChromeActivity.getTabModelSelector()
ChromeActivity.getTabCreator(false)
TabModelSelector
TabModelUtils
TabCreator
TabClosureParams
Tab
WebContents.getMainFrame()
RenderFrameHost.executeJavaScriptInIsolatedWorld()
IsolatedWorldIds
```

Before patching a checkout run:

```bash
python3 scripts/check_chromium_checkout.py /path/to/chromium/src
```

The checker verifies both API symbols and the one source hook anchor AIHub needs.

## The one source hook

`scripts/apply_chrome_overlay.py` locates:

```text
ChromeTabbedActivity.performPostInflationStartup()
```

and inserts one fully qualified call immediately after Chromium assigns its control container:

```java
com.yagay.aihub.chromium.AiHubChromeHook.attach(this);
```

The patcher is idempotent and marker-based. If Chromium moves the anchor, it fails rather than guessing another insertion point.

`ChromeTabbedActivity2` inherits from `ChromeTabbedActivity`, so the base hook also covers that activity path without a second AIHub implementation.

## Java source integration

AIHub does not maintain a parallel GN Android application target. The patcher adds these sources to Chromium's existing `chrome_java_sources` list:

```text
//aihub/aihub-core/src/main/java/**/*.java
//aihub/aihub-android/java/**/*.java
//aihub/generated/java/.../AiHubGeneratedRules.java
//aihub/chromium-overlay/.../AiHubChromeBridge.java
//aihub/chromium-overlay/.../AiHubChromeHook.java
```

Only the last two files may know Chromium internals.

## Generated provider configuration

Provider JSON stays in the AIHub repository:

```text
chromium-overlay/assets/aihub/providers/*.json
```

At overlay time the script validates/compacts those files and generates:

```text
//aihub/generated/java/com/yagay/aihub/generated/AiHubGeneratedRules.java
```

The same generated class carries the configured Ed25519 public key. This avoids coupling AIHub to Chromium asset/resource targets.

The stable Android loader accesses that generated class through a tiny reflection boundary, so its source can still be checked outside a Chromium checkout.

## Real Chrome tabs

One AI provider maps to one retained normal tab ID. AIHub does not create one browser profile per provider.

This means all providers use the normal Chrome profile by default and therefore normal Chrome browser capabilities. Website login identity is whatever the user signs into on that site.

Provider switching is a tab selection operation, not a browser-engine swap.

## JavaScript / DOM actions

AIHub resolves the current `Tab`, obtains its current `WebContents` main frame and calls:

```text
RenderFrameHost.executeJavaScriptInIsolatedWorld(...)
```

The generic script finds the active AI composer/actions semantically and then uses provider JSON selectors as fallback.

Do not replace this with a provider-specific Java controller unless configuration/semantic resolution genuinely cannot express the website behavior.

## Build flow

Prepare Chromium using the normal Android instructions and create an output directory whose args include:

```text
target_os = "android"
```

Then:

```bash
bash scripts/build_aihub_chromium.sh /path/to/chromium/src out/Default
```

This performs:

```text
AIHub repo validation
→ Chromium seam compatibility check
→ sync repository to //aihub
→ generate provider rules
→ patch chrome_java_sources.gni
→ patch one ChromeTabbedActivity hook
→ gn desc //chrome/android:chrome_public_apk
→ autoninja chrome_public_apk
```

Install/launch:

```bash
bash scripts/build_install_aihub.sh /path/to/chromium/src out/Default [DEVICE_SERIAL]
```

The install script uses Chromium's generated `out/Default/bin/chrome_public_apk` runner.

## Upgrade procedure

For each Chromium revision:

```text
1. sync/update Chromium normally
2. run check_chromium_checkout.py before applying the overlay
3. if green, run build_aihub_chromium.sh
4. if checker fails, compare only the reported upstream API/anchor
5. adapt AiHubChromeBridge or apply_chrome_overlay.py
6. update checker to the newly verified surface
7. do not add revision conditionals to aihub-core/aihub-android
```

## Failure classification

```text
check_chromium_checkout.py fails
→ Chromium API/hook surface moved; adapt the seam

overlay script fails
→ source-list or ChromeTabbedActivity patch anchor moved

chrome_public_apk compile fails in AIHub source
→ bridge/API mismatch or stable-source Java issue

build succeeds but normal Chrome features fail without AIHub interaction
→ upstream Chromium/build/device issue

build succeeds; AI switching/DOM actions fail
→ AIHub bridge/rule/runtime issue
```

## Verification boundary

Repository CI cannot substitute for a real Chromium build. A revision becomes "integration tested" only after `chrome_public_apk` compiles and the device checklist passes on that specific revision/device.
