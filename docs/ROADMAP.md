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

## Phase 2 — AI-first Android shell ✅ source-complete, Chromium build pending

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
- [ ] verify full GN/Ninja build against a selected live Chromium checkout
- [ ] verify login/session restoration on a physical Android device

## Phase 3 — thin Chromium integration seam ✅ repository architecture complete

- [x] stable core has no Chromium dependency
- [x] direct `org.chromium.webengine.*` imports restricted to `WebEngineSessionRuntime` and `AiWebEngineHost`
- [x] repository validator rejects Chromium API leakage into other files
- [x] deterministic provider profile/persistence IDs
- [x] Chromium compatibility checker
- [ ] execute against a current real Chromium revision
- [ ] adapt only the two Chromium seam files if upstream WebEngine APIs changed

## Phase 4 — provider maintenance ✅ core mechanism complete

- [x] JSON provider rule loader
- [x] deterministic provider ordering
- [x] generic semantic element resolution with selector fallback
- [x] provider health probe
- [x] diagnostics JSON export
- [x] Ed25519-signed rule bundle verification
- [x] monotonically increasing rule versions
- [x] previous-bundle rollback
- [x] rule-signing helper script and CI round-trip verification
- [ ] choose and package the production rule-signing public key
- [ ] optionally add a production HTTPS distribution endpoint for signed bundles

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
- [ ] Android shortcuts/widgets
- [ ] external event callbacks/subscriptions

## Phase 6 — Chromium browser capability completion

The UI remains AI-specific, but browser capability should stay in the Chromium adapter layer:

- [ ] loading/progress state callbacks
- [ ] browser permission prompts
- [ ] camera/microphone permission flow
- [ ] download handling
- [ ] popup/new-window handling where required by login flows
- [ ] renderer crash recovery
- [ ] memory-pressure/session freezing policy
- [ ] verify file chooser and OAuth/login flows on all built-in AI websites

## Phase 7 — advanced AI workflows

Only after the provider/session/browser layer is stable:

- [ ] resend the same prompt to another AI
- [ ] multi-provider fan-out
- [ ] compare answers
- [ ] unified answer extraction
- [ ] export/search normalized conversation history
- [ ] optional voice/share/shortcut surfaces using the same `AiCommandBus`
