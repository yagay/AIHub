# Roadmap

## Phase 1 — foundation (included in this starter)

- unified provider model
- unified account model
- workspace model
- one session switcher
- command bus
- generic DOM action generator
- WebEngine runtime boundary
- external API contract/AIDL skeleton

## Phase 2 — first runnable Chromium shell (in progress)

- [x] AI Hub activity/shell layout
- [x] provider picker
- [x] account picker
- [ ] workspace selector
- [x] retained tabs via persistence id
- [x] WebEngine profile creation
- [x] login/profile persistence architecture
- [x] bottom unified composer
- [x] previous/next provider controls
- [ ] navigation/loading UI polish
- [ ] build verification against selected Chromium revision

## Phase 3 — browser completeness

- uploads/downloads
- permission prompts
- camera/microphone
- file chooser
- back/forward/refresh
- crash/session restore
- memory pressure tab freezing

## Phase 4 — provider rule engine

- JSON rule loader
- remote signed rule update
- rollback
- rule diagnostics/export
- selector health test
- provider patch SPI

## Phase 5 — external integrations

- [ ] Binder caller identity + permission UI
- [x] token-gated Tasker/MacroDroid/ShortX-compatible intents
- [x] Android text shares
- [x] token-gated deep links
- [ ] shortcuts/widgets
- [ ] event callbacks

## Phase 6 — advanced AI workflows

- resend same prompt to another provider
- compare answers
- multi-provider fan-out
- unified answer extraction
- export/search history
