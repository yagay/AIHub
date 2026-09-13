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
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
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
    private final TextView diagnostics;
    private final Handler main = new Handler(Looper.getMainLooper());

    private volatile boolean pageReady;
    private volatile boolean stickyStatus;
    private volatile String lastConsoleError = "";
    private volatile String lastLoadedUrl = "";
    private volatile Runnable diagnosticAction;
    private volatile int insetLeft;
    private volatile int insetTop;
    private volatile int insetRight;
    private volatile int insetBottom;
    private volatile int imeBottom;

    public UiHost(ComponentActivity activity) {
        this.activity = activity;
        root = new FrameLayout(activity);
        root.setBackgroundColor(Color.rgb(21, 21, 21));

        // Android 15+ enforces edge-to-edge for modern target SDKs. Keep the window
        // edge-to-edge, but move all interactive AIHub content into the safe area.
        // This handles status bars, display cutouts, gesture/3-button navigation and IME.
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets safe = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());

            insetLeft = safe.left;
            insetTop = safe.top;
            insetRight = safe.right;
            insetBottom = safe.bottom;
            imeBottom = ime.bottom;

            int bottom = Math.max(safe.bottom, ime.bottom);
            if (view.getPaddingLeft() != safe.left
                    || view.getPaddingTop() != safe.top
                    || view.getPaddingRight() != safe.right
                    || view.getPaddingBottom() != bottom) {
                view.setPadding(safe.left, safe.top, safe.right, bottom);
            }
            return windowInsets;
        });

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
                lastLoadedUrl = url == null ? "" : url;
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

        // Always stays above the WebView/status layer so diagnostics are reachable even on a blank UI.
        // The root itself is inset-aware, therefore this button never sits under the status bar/cutout.
        diagnostics = new TextView(activity);
        diagnostics.setText("诊断");
        diagnostics.setTextColor(Color.WHITE);
        diagnostics.setTextSize(12f);
        diagnostics.setGravity(Gravity.CENTER);
        diagnostics.setPadding(dp(12), dp(7), dp(12), dp(7));
        diagnostics.setBackgroundColor(Color.argb(210, 45, 45, 45));
        diagnostics.setOnClickListener(v -> {
            Runnable action = diagnosticAction;
            if (action != null) action.run();
        });
        FrameLayout.LayoutParams diagnosticParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        diagnosticParams.gravity = Gravity.TOP | Gravity.END;
        diagnosticParams.topMargin = dp(8);
        diagnosticParams.rightMargin = dp(10);
        root.addView(diagnostics, diagnosticParams);

        // Ask for the initial inset dispatch after all children are attached.
        ViewCompat.requestApplyInsets(root);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    public View view() {
        return root;
    }

    public void setDiagnosticAction(Runnable action) {
        diagnosticAction = action;
    }

    /** Safe state snapshot; contains no chat contents or browser credentials. */
    public String diagnosticSnapshot() {
        return "pageReady=" + pageReady + "\n"
                + "stickyStatus=" + stickyStatus + "\n"
                + "lastLoadedUrl=" + lastLoadedUrl + "\n"
                + "lastConsoleError=" + lastConsoleError + "\n"
                + "systemInsets=" + insetLeft + "," + insetTop + ","
                + insetRight + "," + insetBottom + "\n"
                + "imeBottom=" + imeBottom + "\n";
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
            status.setOnClickListener(null);
            status.setText(text == null ? "" : text);
            status.setVisibility(View.VISIBLE);
        });
    }

    public void showTransientStatus(String text) {
        activity.runOnUiThread(() -> {
            stickyStatus = false;
            status.setOnClickListener(null);
            status.setText(text == null ? "" : text);
            status.setVisibility(View.VISIBLE);
            main.postDelayed(() -> {
                if (!stickyStatus && pageReady) status.setVisibility(View.GONE);
            }, 1200);
        });
    }

    /** Non-fatal setup warning: the web UI remains usable after tapping through. */
    public void showDismissibleWarning(String text) {
        activity.runOnUiThread(() -> {
            stickyStatus = true;
            status.setText((text == null ? "" : text) + "\n\n点击继续使用 AIHub");
            status.setVisibility(View.VISIBLE);
            status.setOnClickListener(v -> {
                stickyStatus = false;
                status.setOnClickListener(null);
                status.setVisibility(View.GONE);
            });
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
