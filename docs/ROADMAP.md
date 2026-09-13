# Roadmap

## Phase 1 — provider-only stable core ✅

- [x] unified provider model
- [x] one retained session per provider
- [x] single `SessionManager` switching path
- [x] `AiCommandBus` shared by UI and external callers
- [x] generic DOM action engine
- [x] browser runtime boundary
- [x] provider-only external API/AIDL
- [x] multi-account/workspace abstractions removed
- [x] provider-specific names kept out of stable core

## Phase 2 — AI-first Android shell ✅ repository source complete

- [x] horizontal AI quick-switch rail
- [x] current AI title
- [x] retained browser session/profile per AI
- [x] shared composer
- [x] new chat / stop / back / forward / reload
- [x] Android system file picker
- [x] multi-file attachment bridge
- [x] attach-and-send ordering
- [x] custom AI website entry
- [x] diagnostics/tools menu
- [ ] verify full GN/Ninja build against the selected live Chromium checkout
- [ ] verify login/session restoration on a physical Android device

## Phase 3 — Chromium-independent architecture ✅ repository complete

- [x] `aihub-core` has no Android/Chromium dependency
- [x] direct `org.chromium.webengine.*` imports restricted to `AiWebEngineHost.java`
- [x] `WebEngineSessionRuntime` contains no upstream Chromium types
- [x] repository validator rejects Chromium API leakage into other files
- [x] deterministic provider profile/persistence IDs
- [x] active `TabManager` resolved at operation time instead of caching the first Tab forever
- [x] attachment transaction pins one active tab for the duration of one upload
- [x] Chromium compatibility checker matches the exact Host API surface
- [ ] execute the checker/build against the chosen real Chromium revision
- [ ] adapt `AiWebEngineHost.java` only if that revision changed Java APIs
- [ ] adapt `BUILD.gn` only if upstream target/dependency names moved

## Phase 4 — provider maintenance ✅ repository complete

- [x] JSON provider rule loader
- [x] deterministic provider ordering
- [x] generic semantic element resolution with selector fallback
- [x] provider health probe
- [x] diagnostics JSON export
- [x] Ed25519-signed rule bundle verification
- [x] monotonically increasing rule versions
- [x] previous-bundle rollback
- [x] rule-signing helper script and CI round-trip verification
- [ ] choose and package the production rule-signing public key before remote distribution
- [ ] optional production HTTPS distribution endpoint for signed bundles

## Phase 5 — external integrations ✅ provider-only base API complete

- [x] token-gated unattended Android Intents
- [x] tokenless deep links with explicit user confirmation
- [x] provider-only AIDL/Binder service
- [x] current provider/session query API
- [x] Android share-sheet entry
- [x] explicit confirmation for shares and deep links
- [x] guarded exported EntryActivity + unexported ShellActivity
- [x] repeated `singleTop` intents revalidated through `onNewIntent`
- [ ] optional per-caller package/signature permission UI in addition to the client token
- [ ] optional Android shortcuts/widgets
- [ ] optional external event callbacks/subscriptions

## Phase 6 — Chromium browser capability preservation ✅ repository policy prepared; device verification pending

AIHub does not rebuild Chromium browser subsystems. Repository-side preparation now includes:

- [x] camera capability declared in host manifest
- [x] microphone/WebRTC capability declared in host manifest
- [x] coarse/fine location capability declared in host manifest
- [x] Android notification capability declared in host manifest
- [x] active-tab-safe OAuth/popup architecture
- [x] browser capability ownership matrix documented
- [x] real-device integration checklist updated
- [x] WebEngine API compatibility check runs before build

The following are selected-revision/device verification tasks, not missing provider implementations:

- [ ] verify Chromium/WebEngine permission prompts on-device
- [ ] verify camera/microphone/location on supported AI websites
- [ ] verify downloads before claiming download support
- [ ] verify autofill/password-manager and safe-browsing behavior
- [ ] verify popup/new-window/native-intent login paths
- [ ] verify renderer crash/session recovery
- [ ] verify memory-pressure behavior with several retained provider sessions
- [ ] wire a custom loading/progress indicator only if the selected revision's verified observer API is needed by the AI-first UI

If any item requires embedder code, implement it once in `AiWebEngineHost.java`, never per provider.

## Phase 7 — advanced AI workflows (optional after base integration is proven)

- [ ] resend the same prompt to another AI
- [ ] multi-provider fan-out
- [ ] compare answers
- [ ] unified answer extraction
- [ ] export/search normalized conversation history
- [ ] optional voice/share/shortcut surfaces using the same `AiCommandBus`

## Definition of repository-side completion

Repository-side work is complete when CI is green for the provider-only core, security/wiring invariants, rule tooling, one-file Chromium API seam, build/install scripts and documentation.

A real Chromium `autoninja` build and device behavior cannot be honestly marked complete without an actual compatible Chromium Android checkout and target device. Those final integration results must be recorded using `INTEGRATION_TEST_CHECKLIST.md`.
