package com.yagay.aihub.bridge;

import android.webkit.JavascriptInterface;
import android.webkit.WebView;

import com.yagay.aihub.browser.BrowserEngine;

import org.json.JSONObject;

import java.util.concurrent.atomic.AtomicLong;

/** Small IPC boundary between the reused NextChat UI and native browser automation. */
public final class AiHubJsBridge {
    private final WebView webView;
    private final BrowserEngine engine;
    private final AtomicLong sequence = new AtomicLong();

    public AiHubJsBridge(WebView webView, BrowserEngine engine) {
        this.webView = webView;
        this.engine = engine;
    }

    @JavascriptInterface
    public String invoke(String raw) {
        String requestId = "native_" + sequence.incrementAndGet();
        try {
            JSONObject command = new JSONObject(raw == null ? "{}" : raw);
            String action = command.optString("action", "");
            if ("chat".equals(action)) {
                String model = command.optString("model", "chatgpt-web");
                String prompt = command.optString("prompt", "");
                String id = requestId;
                webView.post(() -> engine.chat(id, model, prompt, new BrowserEngine.ChatListener() {
                    @Override
                    public void onUpdate(String fullText, String chunk) {
                        emit(event(id, "chat.update").put("text", fullText).put("chunk", chunk));
                    }

                    @Override
                    public void onFinish(String fullText) {
                        emit(event(id, "chat.done").put("text", fullText));
                    }

                    @Override
                    public void onError(String code, String message) {
                        emit(event(id, "chat.error").put("code", code).put("message", message));
                    }
                }));
                return id;
            }
            if ("cancel".equals(action)) {
                String target = command.optString("requestId", "");
                if (!target.isBlank()) engine.cancel(target);
                return requestId;
            }
            if ("open".equals(action)) {
                engine.openProvider(command.optString("model", "chatgpt-web"));
                return requestId;
            }
            emit(event(requestId, "native.error").put("message", "Unknown action: " + action));
        } catch (Exception error) {
            emit(event(requestId, "native.error").put("message", error.getMessage()));
        }
        return requestId;
    }

    public void emitReady() {
        emit(event("system", "native.ready"));
    }

    private static JSONObject event(String requestId, String type) {
        JSONObject event = new JSONObject();
        try {
            event.put("requestId", requestId);
            event.put("type", type);
        } catch (Exception ignored) {}
        return event;
    }

    private void emit(JSONObject event) {
        String script = "window.__AIHubNativeDispatch && window.__AIHubNativeDispatch(" + event.toString() + ");";
        webView.post(() -> webView.evaluateJavascript(script, null));
    }
}
