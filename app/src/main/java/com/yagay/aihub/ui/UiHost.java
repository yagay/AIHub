package com.yagay.aihub.ui;

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

/** NextChat is only the UI renderer. AI websites never run inside this WebView. */
public final class UiHost {
    private static final String UI_URL = "https://appassets.androidplatform.net/assets/ui/index.html";
    private final WebView webView;

    public UiHost(ComponentActivity activity) {
        webView = new WebView(activity);
        WebView.setWebContentsDebuggingEnabled(false);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

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
                return !"appassets.androidplatform.net".equalsIgnoreCase(uri.getHost());
            }
        });
        webView.setWebChromeClient(new WebChromeClient());
    }

    public WebView view() { return webView; }
    public void start() { webView.loadUrl(UI_URL); }

    public boolean handleBack() {
        if (!webView.canGoBack()) return false;
        webView.goBack();
        return true;
    }

    public void destroy() {
        webView.stopLoading();
        webView.destroy();
    }
}
