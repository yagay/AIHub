import {
  ChatOptions,
  LLMApi,
  LLMModel,
  LLMUsage,
  MultimodalContent,
  SpeechOptions,
} from "../api";

interface NativeBridge {
  invoke(payload: string): string;
}

interface NativeEvent {
  requestId: string;
  type: string;
  text?: string;
  chunk?: string;
  code?: string;
  message?: string;
}

interface PendingChat {
  model: string;
  settled: boolean;
  onUpdate?: (message: string, chunk: string) => void;
  onFinish: (message: string, response: Response) => void;
  onError?: (error: Error) => void;
}

declare global {
  interface Window {
    AIHubNative?: NativeBridge;
    __AIHubNativeDispatch?: (event: NativeEvent) => void;
    __AIHubNativeDispatchInstalled?: boolean;
  }
}

const provider = {
  id: "aihub-browser",
  providerName: "OpenAI",
  providerType: "web",
  sorted: 0,
};

const WEB_MODELS: LLMModel[] = [
  { name: "chatgpt-web", displayName: "ChatGPT", available: true, provider, sorted: 1 },
  { name: "gemini-web", displayName: "Gemini", available: true, provider, sorted: 2 },
  { name: "claude-web", displayName: "Claude", available: true, provider, sorted: 3 },
  { name: "deepseek-web", displayName: "DeepSeek", available: true, provider, sorted: 4 },
  { name: "grok-web", displayName: "Grok", available: true, provider, sorted: 5 },
];

const pending = new Map<string, PendingChat>();

function installDispatch() {
  if (typeof window === "undefined" || window.__AIHubNativeDispatchInstalled) return;
  window.__AIHubNativeDispatchInstalled = true;
  window.__AIHubNativeDispatch = (event: NativeEvent) => {
    if (!event || !event.requestId) return;
    const chat = pending.get(event.requestId);
    if (!chat || chat.settled) return;

    if (event.type === "chat.update") {
      chat.onUpdate?.(event.text ?? "", event.chunk ?? "");
      return;
    }

    if (event.type === "chat.done") {
      chat.settled = true;
      pending.delete(event.requestId);
      chat.onFinish(event.text ?? "", new Response(null, { status: 200 }));
      return;
    }

    if (event.type === "chat.error") {
      chat.settled = true;
      pending.delete(event.requestId);
      if (event.code === "login_required" || event.code === "browser_not_ready") {
        try {
          nativeInvoke({ action: "open", model: chat.model });
        } catch (_) {}
      }
      chat.onError?.(new Error(event.message || event.code || "Browser automation failed"));
    }
  };
}

function nativeInvoke(payload: unknown): string {
  if (typeof window === "undefined" || !window.AIHubNative) {
    throw new Error("AIHub native browser bridge is not available");
  }
  return window.AIHubNative.invoke(JSON.stringify(payload));
}

function contentText(content: string | MultimodalContent[]): string {
  if (typeof content === "string") return content;
  return content
    .map((part) => {
      if (part.type === "text") return part.text ?? "";
      if (part.type === "image_url") return "[Image attachment]";
      return "";
    })
    .filter(Boolean)
    .join("\n");
}

function browserPrompt(options: ChatOptions): string {
  const transcript = options.messages
    .map((message) => {
      const role = message.role === "assistant" ? "Assistant" : message.role === "system" ? "System" : "User";
      return `${role}: ${contentText(message.content)}`;
    })
    .join("\n\n");

  return [
    "Continue the conversation below. Follow system instructions when present. Reply only to the final user request and do not describe this transcript wrapper.",
    "",
    transcript,
  ].join("\n");
}

export class AIHubBrowserApi implements LLMApi {
  async chat(options: ChatOptions): Promise<void> {
    installDispatch();
    const controller = new AbortController();
    options.onController?.(controller);

    let requestId = "";
    try {
      requestId = nativeInvoke({
        action: "chat",
        model: options.config.model || "chatgpt-web",
        prompt: browserPrompt(options),
      });
    } catch (error) {
      options.onError?.(error instanceof Error ? error : new Error(String(error)));
      return;
    }

    const chat: PendingChat = {
      model: options.config.model || "chatgpt-web",
      settled: false,
      onUpdate: options.onUpdate,
      onFinish: options.onFinish,
      onError: options.onError,
    };
    pending.set(requestId, chat);

    controller.signal.addEventListener(
      "abort",
      () => {
        if (chat.settled) return;
        chat.settled = true;
        pending.delete(requestId);
        try { nativeInvoke({ action: "cancel", requestId }); } catch (_) {}
        options.onError?.(new Error("aborted"));
      },
      { once: true },
    );
  }

  async speech(_options: SpeechOptions): Promise<ArrayBuffer> {
    throw new Error("Speech is not available in AIHub browser mode");
  }

  async usage(): Promise<LLMUsage> {
    return { used: 0, total: 0 };
  }

  async models(): Promise<LLMModel[]> {
    return WEB_MODELS;
  }
}
