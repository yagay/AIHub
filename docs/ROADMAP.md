# Roadmap

## Phase 1 — stable core ✅

- [x] unified provider model
- [x] unified account model
- [x] workspace model
- [x] single SessionManager switching path
- [x] AiCommandBus shared by UI and external callers
- [x] generic DOM action engine
- [x] WebEngine runtime boundary
- [x] external API contract/AIDL
- [x] provider-specific names kept out of stable core

## Phase 2 — Android/WebEngine shell ✅ source-complete, integration build pending

- [x] unified shell layout
- [x] provider picker and previous/next AI switching
- [x] account picker and isolated profile names
- [x] workspace selector/mappings
- [x] account/workspace rename and safe removal flows
- [x] retained tabs via persistence ID
- [x] login/profile persistence architecture
- [x] shared composer
- [x] new chat / stop / back / forward / reload
- [x] Android system file picker
- [x] multi-file attachment bridge
- [x] attach-and-send ordering
- [x] custom AI website entry
- [ ] verify full GN/Ninja build against a selected live Chromium checkout
- [ ] verify login/session restoration on a physical Android device

## Phase 3 — provider maintenance ✅ core mechanism complete

- [x] JSON provider rule loader
- [x] deterministic provider ordering
- [x] generic semantic element resolution with selector fallback
- [x] provider health probe
- [x] diagnostics JSON export
- [x] Ed25519-signed rule bundle verification
- [x] monotonically increasing rule versions
- [x] previous-bundle rollback
- [x] rule-signing helper script
- [ ] choose and package the production rule-signing public key
- [ ] optionally add a production HTTPS distribution endpoint for signed bundles

## Phase 4 — external integrations ✅ base API complete

- [x] token-gated unattended Android Intents
- [x] tokenless deep links with explicit user confirmation
- [x] AIDL/Binder service
- [x] provider/account/session query APIs
- [x] Android share-sheet entry
- [x] explicit confirmation for ordinary shares
- [x] exported guarded EntryActivity + unexported ShellActivity
- [x] one-shot internal command dispatch to prevent replay after Activity recreation
- [x] repeated `singleTop` EntryActivity intents revalidated through `onNewIntent`
- [ ] optional per-caller package/signature permission UI in addition to the client token
- [ ] Android shortcuts/widgets
- [ ] external event callbacks/subscriptions

## Phase 5 — build and regression tooling ✅ repository side complete

- [x] provider-rule validation
- [x] architecture/wiring validation
- [x] security invariants in repository validation
- [x] shell/Python syntax validation
- [x] core smoke tests
- [x] GitHub Actions on pushes and pull requests
- [x] Chromium WebEngine API compatibility checker
- [x] safe sync into `chromium/src/aihub`
- [x] build-only script
- [x] install-only script with multi-device selection
- [x] one-command build + install script
- [ ] execute the one-command flow in a real Chromium Android checkout
- [ ] capture and fix any Chromium-revision-specific WebEngine API/BUILD changes

## Phase 6 — browser/runtime hardening after first real device build

These tasks should be driven by actual Chromium/device behavior rather than guessed before integration testing:

- [ ] loading/progress state callbacks
- [ ] browser permission prompts (camera/microphone/location where required by provider sites)
- [ ] download handling
- [ ] memory-pressure/session freezing policy
- [ ] renderer crash recovery validation
- [ ] WebEngine profile-data deletion if/when the selected upstream API exposes a safe supported operation

## Phase 7 — advanced AI workflows

Only after the base website/session layer is proven stable:

- [ ] resend the same prompt to another AI
- [ ] multi-provider fan-out
- [ ] compare answers
- [ ] unified answer extraction
- [ ] export/search normalized conversation history
- [ ] optional voice/share/shortcut surfaces using the same AiCommandBus
