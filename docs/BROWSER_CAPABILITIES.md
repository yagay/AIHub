# Chromium browser capability policy

AIHub is an AI-oriented shell around Chromium WebEngine, not a replacement browser engine. The default rule is:

> If the selected WebEngine revision already owns a browser capability, AIHub should expose/retain it rather than reimplementing it per AI provider.

Provider-specific code is reserved for AI-page interaction rules (composer/send/new-chat/stop), not general browser behavior.

## Capability matrix

| Capability | AIHub repository state | Owner | Real-device verification |
| --- | --- | --- | --- |
| HTML/JS/rendering | delegated | Chromium/WebEngine | required with selected revision |
| cookies / localStorage / IndexedDB | retained per provider profile | Chromium/WebEngine | required |
| provider session restoration | deterministic profile + persistence ID | Chromium/WebEngine + host seam | required |
| back / forward / reload | wired to current active Chromium tab | Chromium/WebEngine | required |
| OAuth / sign-in redirects | active-tab-safe host architecture | Chromium/WebEngine | required for each login provider |
| popup/new active tab | AIHub resolves `TabManager.getActiveTab()` per operation | Chromium/WebEngine | required |
| AIHub text send/new-chat/stop | generic DOM engine + provider rules | AIHub | provider tests required |
| Android document attachment | common bridge implemented | AIHub + Chromium page | provider tests required |
| camera | host capability declared; no silent grant | Chromium/WebEngine permission flow | required |
| microphone / WebRTC | host capability declared; no silent grant | Chromium/WebEngine permission flow | required |
| geolocation | host capability declared; no silent grant | Chromium/WebEngine permission flow | required |
| notifications | host capability declared | Chromium/WebEngine / Android | required |
| autofill / password-manager behavior | do not duplicate | selected WebEngine revision | required |
| safe-browsing behavior | do not duplicate | selected WebEngine revision | required |
| downloads | do not add provider-specific downloader | selected WebEngine revision / host seam | required before claiming support |
| renderer crash recovery | retained persistence architecture; no speculative replacement | WebEngine + host seam | required |
| loading/progress indication | intentionally waits for verified observer API in selected revision | host seam | pending integration verification |

## Active-tab invariant

AIHub must never assume that the first `Tab` opened for a provider remains the browser's active page forever.

The host stores the provider's `TabManager`. Every normal browser operation resolves the current active tab at operation time. This is important for OAuth, authentication popups and websites that activate a new tab/window.

A single attachment transaction is the exception: it snapshots the active tab once at upload start and keeps the whole upload on that same page.

## Permission policy

The manifest declares browser-relevant capabilities such as camera, microphone, coarse/fine location and Android notifications. Declaration is not permission.

AIHub must not silently grant a site access. The selected Chromium/WebEngine revision remains responsible for its browser/site permission path, with Android runtime behavior verified on a real device. If a revision requires an embedder callback for a permission, that callback belongs only in `AiWebEngineHost.java` (or a host helper owned exclusively by it), never in provider-specific code.

## Login policy

AIHub does not intercept passwords, OAuth tokens or cookies. Users sign in on the real AI website. Sign-in redirects and popup/new-tab behavior must stay inside Chromium/WebEngine.

The AIHub command layer sees only provider IDs and high-level actions. Third-party AIHub integrations never receive website credentials or browser storage.

## Download policy

Do not create one downloader for ChatGPT, another for Claude, etc. First verify the selected WebEngine revision's download behavior. If embedder code is required, implement it once in the Chromium host seam and expose only generic status/UI to AIHub.

## Upgrade policy

When a Chromium revision changes browser behavior:

1. verify the upstream WebEngine sample;
2. run `scripts/check_chromium_checkout.py`;
3. adapt `AiWebEngineHost.java` if Java APIs changed;
4. adapt `chromium-overlay/BUILD.gn` if upstream target names/dependencies moved;
5. update the compatibility checker;
6. keep `aihub-core`, provider switching and the AI-first UI revision-independent.

## Completion boundary

Repository-side architecture, provider-only sessions, active-tab handling, browser capability declarations, security boundaries, rule tooling and CI can be completed without a local Chromium tree.

The following cannot be truthfully marked complete until a real compatible Chromium Android checkout and device are available:

- final GN/`autoninja` compilation for the chosen revision;
- installation of the matching WebEngine support APK;
- camera/microphone/location permission behavior;
- downloads;
- OAuth/popup behavior for each supported AI login route;
- renderer crash/session restoration;
- exact navigation/loading observer integration.

Those are integration tests, not reasons to duplicate Chromium subsystems inside AIHub.
