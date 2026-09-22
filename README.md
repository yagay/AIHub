# AIHub

AIHub is a clean-room Android workspace for using official AI web apps through a native multi-window chat interface.

## Current architecture

AIHub 0.2.x is window-first.

- **One login per provider.** AIHub no longer has an account-management layer.
- **Multiple live chat windows.** Every open chat window owns its own WebView instance.
- **No reload on normal window switch.** Switching tabs detaches the previous WebView from the visible container and attaches the selected WebView without calling `reload()`, `loadUrl()`, or `destroy()`.
- **Shared provider login state.** Window WebViews intentionally use the shared WebView cookie jar, so ChatGPT windows share the same ChatGPT login, Claude windows share the same Claude login, and so on.
- **Chat / Web dual view.** Each window can switch between AIHub's native chat view and the official website while keeping the same underlying WebView.
- **Background generation.** A window can continue waiting for a provider response while another window is active; completed background windows can be marked unread.
- **Persistent workspace.** Open windows, their provider, title, last conversation URL, active tab, and preferred Chat/Web mode are restored after app restart.
- **Provider adapters stay isolated.** Website DOM logic remains in `app/src/main/assets/providers/`.

## UI model

```text
AIHub Workspace
│
├─ Window tab strip
│   ├─ ChatGPT · Upload issue
│   ├─ ChatGPT · ListCleaner
│   ├─ Claude · Architecture
│   └─ +
│
├─ Active window
│   ├─ Native Chat view
│   └─ Official Web view
│
└─ Drawer
    ├─ New window
    ├─ ChatGPT windows
    ├─ Claude windows
    ├─ Gemini windows
    ├─ Grok windows
    ├─ DeepSeek windows
    ├─ Qwen windows
    └─ Diagnostics
```

## Runtime model

```text
WorkspaceViewModel
       │
       ├─ WindowStore
       ├─ ConversationStore
       └─ WindowWebRuntime
              │
              └─ WebRuntime
                   ├─ windowId -> WebView
                   ├─ Provider navigation policy
                   ├─ Standard WebView file chooser
                   ├─ Native attachment staging
                   ├─ Microphone / camera permissions
                   ├─ HTTP(S) / data / blob downloads
                   └─ Provider JavaScript runtime
```

The important separation is:

```text
Provider login state  = shared WebView cookies
Chat identity         = windowId
Loaded page/DOM       = one persistent WebView per open window
Native conversation   = one local conversation store per windowId
```

## Providers

- ChatGPT
- Claude
- Gemini
- Grok
- DeepSeek
- Qwen

Authentication, subscriptions, model access, provider limits, and website availability remain controlled by the providers' official websites.

## Attachments

AIHub supports both paths:

1. The official website can invoke Android's standard WebView file chooser.
2. AIHub's native attachment button opens Android's document picker and stages the selected files into the active provider page, then fills the website's real `input[type=file]`.

The native UI only records the attachment after the provider page confirms that a file is present.

## WebView lifecycle

Open windows keep their WebView instances alive until the user closes the window or the runtime is destroyed.

Normal switch:

```text
Window A WebView
   detach from host
   keep instance alive

Window B WebView
   attach to host

No reload
No destroy
```

Closing a window destroys only that window's WebView.

## Provider adapter layout

```text
app/src/main/assets/providers/
├── common.js
├── attachment.js
├── response-state.js
├── send-queue.js
├── capabilities.js
├── chatgpt.js
├── claude.js
├── gemini.js
├── grok.js
├── deepseek.js
└── qwen.js
```

Website DOMs change frequently. Keeping selectors and website-specific automation outside the Compose UI lets provider breakage be repaired without rewriting the workspace architecture.

## Build

- JDK 17
- Gradle 9.4.1
- Android Gradle Plugin 9.2.0
- compileSdk / targetSdk 37

```bash
gradle :app:assembleDebug
```

GitHub Actions validates every provider JavaScript file, builds the debug APK, and uploads the `AIHub-debug` artifact on every push to `main`.

## Design references

The implementation is clean-room code. Product and architecture ideas were studied from several open-source projects:

- Cherry Studio: conversation/topic + tab/window product model
- ChatterUI: mobile chat screen and chat drawer patterns
- EinkBro: WebView tab lifecycle and lazy browser-tab ideas
- Fulguris: tab model / tab manager separation

AIHub does not copy those projects' source into its Android implementation.
