package com.yagay.aihub.ui;

import android.content.Intent;
import android.net.Uri;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;

import androidx.activity.ComponentActivity;
import androidx.annotation.Nullable;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

import com.yagay.aihub.bridge.AiHubJsBridge;
import com.yagay.aihub.browser.BrowserEngine;

/** Hosts the static NextChat build. WebView renders UI only; AI websites run in external Chrome. */
public final class UiHost {
    private static final String UI_URL = "https://appassets.androidplatform.net/assets/ui/index.html";

    private final ComponentActivity activity;
    private final WebView webView;
    private final AiHubJsBridge bridge;
    private String pendingSharedText;

    public UiHost(ComponentActivity activity, BrowserEngine engine) {
        this.activity = activity;
        this.webView = new WebView(activity);
        this.bridge = new AiHubJsBridge(webView, engine);

        WebView.setWebContentsDebuggingEnabled(false);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);

        WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(activity))
                .build();
        webView.setWebViewClient(new WebViewClientCompat() {
            @Override
            @Nullable
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if ("appassets.androidplatform.net".equalsIgnoreCase(uri.getHost())) return false;
                String scheme = uri.getScheme();
                if ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) {
                    try { activity.startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (Exception ignored) {}
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                bridge.emitReady();
                applyPendingSharedText();
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
        webView.addJavascriptInterface(bridge, "AIHubNative");
    }

    public WebView view() {
        return webView;
    }

    public void start() {
        webView.loadUrl(UI_URL);
    }

    public boolean handleBack() {
        if (!webView.canGoBack()) return false;
        webView.goBack();
        return true;
    }

    public void handleIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return;
        CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
        if (shared == null || shared.toString().isBlank()) return;
        pendingSharedText = shared.toString();
        applyPendingSharedText();
    }

    private void applyPendingSharedText() {
        if (pendingSharedText == null || pendingSharedText.isBlank()) return;
        String text = JSONObjectQuote.quote(pendingSharedText);
        String script = "(() => { const e=document.querySelector('textarea'); if(!e)return false;"
                + "const s=Object.getOwnPropertyDescriptor(HTMLTextAreaElement.prototype,'value')?.set;"
                + "if(s)s.call(e," + text + ");else e.value=" + text + ";"
                + "e.dispatchEvent(new Event('input',{bubbles:true}));e.focus();return true;})()";
        webView.evaluateJavascript(script, value -> {
            if ("true".equals(value)) pendingSharedText = null;
        });
    }

    public void destroy() {
        webView.removeJavascriptInterface("AIHubNative");
        webView.stopLoading();
        webView.destroy();
    }

    /** Avoid a dependency from UI host to org.json just to quote one JS string. */
    private static final class JSONObjectQuote {
        static String quote(String value) {
            return org.json.JSONObject.quote(value == null ? "" : value);
        }
    }
}
