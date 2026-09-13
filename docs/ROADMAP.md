# Roadmap

## Phase 1 — unified provider core ✅

- [x] one provider model
- [x] one retained session abstraction per provider
- [x] shared `SessionManager`
- [x] shared `AiCommandBus`
- [x] provider-specific names absent from stable controller logic
- [x] account/workspace abstractions removed
- [x] pure Java core with no Android/Chromium dependencies

## Phase 2 — stable AI Android layer ✅ source-complete

- [x] `AiHubBrowserHost` browser boundary
- [x] Chrome-independent `BrowserSessionRuntime`
- [x] generic DOM action engine
- [x] horizontal AI quick-switch UI
- [x] shared composer
- [x] new chat / stop / attach / browser navigation
- [x] Chrome-controls escape hatch
- [x] custom AI website dialog + persistence
- [x] provider JSON codec/loader
- [x] signed rule bundle verification/rollback
- [x] normal attachment path delegates to website/Chrome native file chooser
- [x] Android preselected-URI fallback bridge

## Phase 3 — current Chromium Chrome overlay ✅ repository-side implementation complete

- [x] obsolete standalone browser APK removed
- [x] obsolete browser-embedding runtime removed
- [x] exactly two Chromium-specific Java seam files
- [x] real normal Chrome tabs used for providers
- [x] current tab resolved at operation time
- [x] isolated-world DOM execution through current main frame
- [x] one `ChromeTabbedActivity` attach hook
- [x] AIHub sources injected into `chrome_java_sources.gni`
- [x] provider JSON/public key generated into Java during overlay
- [x] compatibility checker watches the actual upstream surface
- [x] build target changed to `chrome_public_apk`
- [x] install/launch uses Chromium's generated runner
- [ ] compile against a real current Chromium checkout
- [ ] complete physical-device integration checklist

## Phase 4 — provider maintenance ✅ mechanism complete

- [x] ChatGPT / Claude / Gemini / Grok / DeepSeek JSON rules
- [x] semantic input/send/new-chat/stop resolution
- [x] selector fallback
- [x] custom provider metadata
- [x] Ed25519 signed rule envelopes
- [x] monotonic version enforcement
- [x] rollback support
- [x] build-time public-key embedding
- [ ] choose/package a production Ed25519 public key when remote distribution is enabled
- [ ] optionally add a trusted HTTPS distribution endpoint for signed bundles

## Phase 5 — preserve Chromium browser capability 🔎 device verification pending

The architecture intentionally inherits these from the full browser rather than implementing copies:

- [ ] verify OAuth/login flows on built-in providers
- [ ] verify downloads
- [ ] verify native file chooser
- [ ] verify camera/microphone permission flow
- [ ] verify password/autofill UI
- [ ] verify popup/new-window behavior
- [ ] verify media playback
- [ ] verify process/tab restoration
- [ ] verify multi-window activity path

A failure here is an integration regression to diagnose, not an automatic reason to build an AIHub replacement subsystem.

## Phase 6 — polish after first successful full build

Only after the selected Chromium revision builds/runs:

- [ ] tune overlay insets so page content is never obscured by AIHub bars
- [ ] add optional loading/progress indicator from `Tab.getProgress()`
- [ ] improve selected-provider visual state
- [ ] add provider management/edit/remove UI for custom providers
- [ ] add diagnostics export for current provider/tab/rule resolution
- [ ] add optional AIHub enable/disable setting
- [ ] evaluate tablet/foldable/multi-window layout

## Phase 7 — optional external automation

The old standalone Binder/Activity API was intentionally removed with the standalone APK. If automation is reintroduced, it must target the embedded coordinator rather than create a second browser runtime.

Potential future surfaces:

- [ ] Android shortcuts
- [ ] share-sheet routing into current/provider AI
- [ ] MacroDroid/ShortX-friendly explicit Intent receiver attached to the Chrome app
- [ ] callback/event surface that never exposes cookies/auth/browser storage

## Phase 8 — advanced AI workflows

After browser integration is proven stable:

- [ ] resend prompt to another AI
- [ ] multi-provider fan-out
- [ ] compare answers
- [ ] generic answer extraction
- [ ] normalized conversation export/search
- [ ] optional voice and screenshot-to-AI workflows using the same provider/session core
