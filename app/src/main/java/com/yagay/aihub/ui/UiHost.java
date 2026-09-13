package com.yagay.aihub.ui;

import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ConsoleMessage;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.activity.ComponentActivity;
import androidx.annotation.Nullable;
import androidx.webkit.WebResourceErrorCompat;
import androidx.webkit.WebViewAssetLoader;
import androidx.webkit.WebViewClientCompat;

import java.io.InputStream;

/** NextChat is only the UI renderer. AI websites never run inside this WebView. */
public final class UiHost {
    private static final String UI_URL = "https://appassets.androidplatform.net/assets/ui/index.html";

    private final ComponentActivity activity;
    private final FrameLayout root;
    private final WebView webView;
    private final TextView status;
    private final Handler main = new Handler(Looper.getMainLooper());

    private boolean pageReady;
    private boolean stickyStatus;
    private String lastConsoleError = "";

    public UiHost(ComponentActivity activity) {
        this.activity = activity;
        root = new FrameLayout(activity);
        root.setBackgroundColor(Color.rgb(21, 21, 21));

        webView = new WebView(activity);
        webView.setBackgroundColor(Color.rgb(21, 21, 21));
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

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url != null && url.startsWith("https://appassets.androidplatform.net/")) {
                    pageReady = true;
                    main.postDelayed(() -> {
                        if (!stickyStatus && pageReady) status.setVisibility(View.GONE);
                    }, 700);
                }
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceErrorCompat error) {
                if (request != null && request.isForMainFrame()) {
                    String detail = error == null
                            ? "unknown WebView error"
                            : String.valueOf(error.getDescription());
                    showFatal("AIHub UI 加载失败\n" + detail + consoleSuffix());
                }
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request,
                                            WebResourceResponse errorResponse) {
                if (request != null && request.isForMainFrame()) {
                    int statusCode = errorResponse == null ? 0 : errorResponse.getStatusCode();
                    showFatal("AIHub UI HTTP 错误: " + statusCode + consoleSuffix());
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage message) {
                if (message != null && message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                    lastConsoleError = message.message() + " @ " + message.sourceId() + ":" + message.lineNumber();
                }
                return super.onConsoleMessage(message);
            }
        });

        root.addView(webView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        status = new TextView(activity);
        status.setTextColor(Color.WHITE);
        status.setTextSize(15f);
        status.setGravity(Gravity.CENTER);
        status.setPadding(48, 48, 48, 48);
        status.setBackgroundColor(Color.rgb(21, 21, 21));
        root.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public View view() {
        return root;
    }

    public void start() {
        showStatus("正在加载 AIHub UI…\n正在检查 Root / Titanium…", false);
        try (InputStream ignored = activity.getAssets().open("ui/index.html")) {
            webView.loadUrl(UI_URL);
        } catch (Exception error) {
            showFatal("APK 内缺少 UI: " + error.getMessage());
        }
    }

    public void showStatus(String text, boolean sticky) {
        activity.runOnUiThread(() -> {
            stickyStatus = sticky;
            status.setText(text == null ? "" : text);
            status.setVisibility(View.VISIBLE);
        });
    }

    public void showTransientStatus(String text) {
        activity.runOnUiThread(() -> {
            stickyStatus = false;
            status.setText(text == null ? "" : text);
            status.setVisibility(View.VISIBLE);
            main.postDelayed(() -> {
                if (!stickyStatus && pageReady) status.setVisibility(View.GONE);
            }, 1200);
        });
    }

    public void showFatal(String text) {
        showStatus(text, true);
    }

    private String consoleSuffix() {
        return lastConsoleError.isEmpty() ? "" : "\nConsole: " + lastConsoleError;
    }

    public boolean handleBack() {
        if (!webView.canGoBack()) return false;
        webView.goBack();
        return true;
    }

    public void destroy() {
        main.removeCallbacksAndMessages(null);
        webView.stopLoading();
        webView.destroy();
    }
}
