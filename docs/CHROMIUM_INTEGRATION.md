# Chromium / WebEngine integration

This project deliberately targets Chromium **WebEngine** (the successor/merge path of WebLayer) rather than modifying the Android Chrome UI directly.

Why:

- Chromium's Android Chrome UI still has explicit single-regular-profile assumptions.
- WebEngine is Chromium's browser-embedding layer, intended for applications that provide their own UI.
- Its Tab API supports active-tab switching and JavaScript execution, which maps well to a unified AI composer.
- `FragmentParams` has a profile name/persistence model that can be used as the isolation boundary for account containers.

## Recommended upstream checkout

Use a normal Chromium checkout and first verify the stock WebEngine shell before adding AI Hub.

Typical Chromium setup/build flow:

```bash
fetch --nohooks android
gclient sync
cd src
. build/android/envsetup.sh
gn gen out/Default
```

Verify the current WebEngine shell target in the checked-out revision. Recent Chromium documentation uses:

```bash
autoninja -C out/Default run_webengine_shell_local
out/Default/bin/run_webengine_shell_local
```

The WebEngine API is still under development, so keep all direct `org.chromium.webengine.*` calls inside `chromium-overlay`. That is why `aihub-core` has no Chromium dependency.

## Integration order

1. Copy `aihub-core` sources into an Android library target in the Chromium tree.
2. Add the AI Hub Android shell UI under the WebEngine shell or a new sibling shell target.
3. Implement `WebEngineSessionRuntime.WebEngineHost` using the exact WebEngine API from that Chromium revision.
4. Create one isolated WebEngine profile/container per `AiAccount.profileName`.
5. Keep a retained Tab per `AiSessionKey`.
6. Wire provider/account/workspace switchers to `SessionManager` only.
7. Wire the bottom composer to `AiCommandBus`.
8. Add Binder/Intent adapters after the in-app path passes smoke tests.

## Important restriction

Do not copy WebEngine implementation classes into the app. Build against the public WebEngine API in the Chromium checkout. The API is evolving, so the host adapter is intentionally a small seam.

## AIHub GN shell target

The repository now includes `chromium-overlay/BUILD.gn` and an Android manifest/resources/activity shell.
When the repository is placed at `src/aihub` in a Chromium checkout, the intended target is:

```bash
autoninja -C out/Default //aihub/chromium-overlay:aihub_apk
```

All code that directly imports `org.chromium.webengine.*` stays in `chromium-overlay/`. If a Chromium revision
renames or changes the WebEngine API, only this layer should need adaptation; `aihub-core` and the third-party
command contract remain unchanged.
