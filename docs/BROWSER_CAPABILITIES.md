# Browser capability policy

AIHub is compiled into the full Chromium Android browser. Browser capabilities are therefore **inherited from Chromium**, not reimplemented by AIHub.

The overlay is allowed to change presentation and AI workflow only. If an AIHub change breaks a normal Chrome capability, treat that as an integration regression.

## Capability ownership

| Capability | Owner | AIHub responsibility |
|---|---|---|
| rendering / JS / network | Chromium | do not replace |
| cookies / localStorage / IndexedDB | Chromium | never copy/export |
| website login / OAuth | Chromium + website | keep normal navigation/tab flow usable |
| downloads | Chromium | do not add a second download manager |
| file chooser | Chromium | click the real website file input |
| camera / microphone / geolocation | Chromium permission/site-settings stack | do not auto-grant/bypass |
| password manager / autofill | Chromium | do not store website passwords |
| popup / new-window / OAuth tabs | Chromium | always resolve actions against current real tab |
| media playback | Chromium | do not replace media pipeline |
| normal tab restore/lifecycle | Chromium | store only provider→tab-id association |
| Safe Browsing/security behavior | Chromium | no bypass layer |
| AI provider switching | AIHub | select retained real Chrome tab |
| unified composer/actions | AIHub | generic DOM engine + JSON fallbacks |
| custom AI metadata | AIHub | name/url/rule metadata only |

## Chrome controls

AIHub hides Chromium's normal control container by default to present an AI-first shell. It does **not** destroy the controls.

The top `Chrome` button toggles the normal controls back on. This provides an escape hatch for browser UI that should remain Chromium-owned, including page/site tools that AIHub does not duplicate.

## Provider tabs

Each provider is associated with a normal non-incognito Chrome tab ID. This is intentionally not a separate browser profile per AI.

Advantages:

- website authentication follows normal Chrome behavior
- popup/new-window flows stay in the same tab model
- browser history/navigation remains Chromium-native
- no duplicated cookie/storage implementation
- lower maintenance cost across Chromium upgrades

If a retained tab is gone, AIHub creates a new normal Chrome tab for the provider homepage and stores its new ID.

## Attachments

### User taps AIHub Attach

```text
AIHub
→ generic script clicks website input[type=file]
→ Chromium native file chooser
→ website receives selected file normally
```

This is the preferred path.

### Android share already contains URIs

When another Android app has already supplied content URIs, `BrowserSessionRuntime` reads those URIs and uses a generic DataTransfer/file-input bridge. This path is limited to 64 MiB aggregate data and exists only for preselected external content.

## DOM execution

AIHub executes generic page actions through the current tab's live main `RenderFrameHost` isolated world.

Reasons:

- avoid depending on provider Java classes
- avoid putting AIHub helper variables into the page's normal JS world
- keep one generic action engine
- resolve the active tab at operation time so login/popup/tab transitions do not leave AIHub targeting a stale initial tab

## Loading state

AIHub does not need a second browser loading engine. The Chrome bridge reads `Tab.isLoading()` and `Tab.getProgress()` directly. Future visual progress indicators should consume those host values rather than add another navigation observer stack unless the UI genuinely needs callbacks.

## What real-device validation must prove

A successful integration should demonstrate that the AI overlay does not regress normal Chromium behavior for:

- account/password login forms
- Google/OAuth login flows used by AI providers
- downloads
- file chooser
- camera/microphone permissions
- geolocation if requested by a site
- popup/new-tab flows
- back/forward/reload
- background/foreground and process restore
- media playback
- Chrome controls toggle

Failures should first be classified as Chromium upstream/device, overlay/hook, bridge, or provider-rule issues before adding new code.
