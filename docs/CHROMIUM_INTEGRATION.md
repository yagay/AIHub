# Chromium / WebEngine integration

AIHub targets Chromium **WebEngine** rather than modifying the Android Chrome UI directly.

This keeps the stable AI/session/account core independent from Chromium API churn while still using Chromium's browser embedding layer for real website login state, navigation and rendering.

## Upstream verification first

Before blaming AIHub for an integration failure, verify that the selected Chromium revision can build and run its own WebEngine sample.

A typical Android checkout setup is:

```bash
fetch --nohooks android
gclient sync
cd src
. build/android/envsetup.sh
gn args out/Default
```

Use Android GN args, including:

```text
target_os = "android"
```

Current Chromium WebEngine documentation uses the local sample flow:

```bash
autoninja -C out/Default run_webengine_shell_local
out/Default/bin/run_webengine_shell_local
```

If the stock WebEngine sample does not build/run on the chosen revision/device, fix that upstream environment first.

## AIHub compatibility seam

Every direct `org.chromium.webengine.*` reference is kept under `chromium-overlay/`.

The stable modules do not depend on WebEngine:

```text
aihub-core/
android-api/
```

When Chromium changes an API, adapt `chromium-overlay/` instead of adding Chromium-version branches to `aihub-core/`.

AIHub currently checks for the WebEngine surface it uses, including:

- `WebSandbox.create()` / fragment creation
- `FragmentParams.setProfileName()`
- `FragmentParams.setPersistenceId()`
- `TabManager.getActiveTab()` / `createTab()`
- `Tab.executeScript()`
- `Tab.getNavigationController()`
- active-tab and display-URI access
- the local WebEngine/WebLayer support target

Run the compatibility check directly with:

```bash
python3 scripts/check_chromium_checkout.py /path/to/chromium/src
```

## Build AIHub

The supported repository-side flow is:

```bash
./scripts/build_aihub_chromium.sh /path/to/chromium/src out/Default
```

The script:

1. validates AIHub provider rules and repository wiring;
2. runs the stable-core smoke test;
3. checks the selected Chromium WebEngine API surface;
4. syncs the repository to `chromium/src/aihub`;
5. verifies that the GN output is Android-targeted;
6. resolves `//aihub/chromium-overlay:aihub_apk` with `gn desc`;
7. builds `//aihub/chromium-overlay:aihub_local`.

`aihub_local` builds both AIHub and the local WebEngine support APK used during Chromium development.

## Build + install in one command

```bash
./scripts/build_install_aihub.sh /path/to/chromium/src out/Default
```

With multiple Android devices connected:

```bash
./scripts/build_install_aihub.sh /path/to/chromium/src out/Default DEVICE_SERIAL
```

The install step discovers the generated APKs, installs them through `adb`, and launches the guarded exported `AiHubEntryActivity`. The real `AiHubShellActivity` intentionally remains unexported.

## Integration debugging rule

If a Chromium revision breaks AIHub, classify the failure before changing code:

```text
stock WebEngine sample fails
→ Chromium/device/environment problem

stock sample works, check_chromium_checkout.py fails
→ WebEngine API/target changed; adapt chromium-overlay

compatibility check passes, GN target fails
→ AIHub BUILD.gn/dependency wiring problem

build succeeds, runtime fails
→ capture AIHub diagnostics + logcat and fix runtime integration
```

Do not add provider-specific or Chromium-revision-specific branches to the stable session/account/command core to work around an embedding-layer failure.
