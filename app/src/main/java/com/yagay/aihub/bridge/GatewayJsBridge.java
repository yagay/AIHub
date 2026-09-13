package com.yagay.aihub.bridge;

import android.content.Intent;
import android.net.Uri;
import android.webkit.JavascriptInterface;

import androidx.activity.ComponentActivity;

import java.util.Map;

/** Minimal UI bridge used only to open supported provider login pages in the real browser. */
public final class GatewayJsBridge {
    private static final Map<String, String> URLS = Map.of(
            "chatgpt-web", "https://chatgpt.com/",
            "claude-web", "https://claude.ai/new",
            "gemini-web", "https://gemini.google.com/app",
            "deepseek-web", "https://chat.deepseek.com/",
            "grok-web", "https://grok.com/"
    );

    private final ComponentActivity activity;

    public GatewayJsBridge(ComponentActivity activity) {
        this.activity = activity;
    }

    @JavascriptInterface
    public void openProvider(String providerId) {
        String url = URLS.get(providerId);
        if (url == null) return;
        activity.runOnUiThread(() -> {
            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                activity.startActivity(intent);
            } catch (Exception ignored) {}
        });
    }
}
