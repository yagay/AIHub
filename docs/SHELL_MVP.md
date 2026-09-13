# AIHub Shell MVP

This phase turns the architecture starter into a real Android shell source layer.

## Included now

- one top-level provider switcher for every AI site
- previous/next AI buttons that reuse `SessionManager.nextProvider()` / `previousProvider()`
- one account switcher shared by every provider
- add-account flow that creates a new isolated WebEngine `profileName`
- persisted account metadata and last selected session
- one shared composer and send action
- new-chat and stop-generation actions
- `ACTION_SEND` text sharing into the current AI
- AIHub custom Intent actions and `aihub://` deep links
- all external commands converted into `AiCommand` before execution

## Multi-account behavior

Account metadata is stored in Android `SharedPreferences`, but login data is not. Cookies, local storage,
IndexedDB and other website state remain owned by the WebEngine profile. Creating another account therefore
creates another browser profile instead of logging the existing profile out.

## Chromium build placement

Place this repository at `src/aihub` inside a Chromium checkout. The supplied GN target expects that layout.

```bash
autoninja -C out/Default //aihub/chromium-overlay:aihub_apk
```

The current WebEngine implementation also needs the WebEngine/WebLayer support package appropriate to the
Chromium revision. The public API is still evolving, so Chromium-facing code intentionally lives only under
`chromium-overlay/`.

## External examples

Long-press the account button in AIHub to view/copy the local third-party client token. Custom actions and deep links require this token; normal Android share-sheet text does not.

Switch provider:

```bash
adb shell am start -n com.yagay.aihub/.chromium.AiHubShellActivity \
  -a com.yagay.aihub.action.SWITCH \
  --es provider_id claude \
  --es client_token YOUR_TOKEN
```

Send text to the current session:

```bash
adb shell am start -n com.yagay.aihub/.chromium.AiHubShellActivity \
  -a com.yagay.aihub.action.SEND_TEXT \
  --es text "Explain this code" \
  --es client_token YOUR_TOKEN
```

Deep link:

```text
aihub://send?provider=gemini&text=hello&token=YOUR_TOKEN
```

## Next engineering phase

1. file chooser / attachment bridge
2. Binder service backed by the same `AiCommandBus`
3. provider/account chips and reorderable favourites
4. navigation/loading state callbacks
5. provider-rule health diagnostics and remote signed rule updates
