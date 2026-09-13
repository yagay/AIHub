package com.yagay.aihub.web;

import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Message;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.activity.ComponentActivity;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

/** Creates every WebView with the same profile, security, popup, file and download policy. */
public final class WebViewFactory {
    public interface Listener {
        void onPageFinished(WebView view, String url);
        void onRendererGone(WebView view);
        void onMessage(String message);
    }

    private WebViewFactory() {}

    public static WebView create(
            ComponentActivity activity,
            String profileName,
            FileChooserCoordinator fileChooser,
            DownloadHandler downloads,
            Listener listener) {
        WebView webView = new WebView(activity);
        configure(activity, webView, profileName, fileChooser, downloads, listener, null, true);
        return webView;
    }

    private static void configure(
            ComponentActivity activity,
            WebView webView,
            String profileName,
            FileChooserCoordinator fileChooser,
            DownloadHandler downloads,
            Listener listener,
            Runnable closeAction,
            boolean reportProfileFallback) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            WebViewCompat.setProfile(webView, profileName);
        } else if (reportProfileFallback) {
            listener.onMessage("This WebView runtime cannot isolate multiple accounts; website data will be shared.");
        }

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);

        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        cookies.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String scheme = uri.getScheme();
                if (scheme == null || scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")
                        || scheme.equalsIgnoreCase("about") || scheme.equalsIgnoreCase("data")
                        || scheme.equalsIgnoreCase("blob")) {
                    return false;
                }
                return openExternal(activity, uri, listener);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                listener.onPageFinished(view, url);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) listener.onMessage("Page failed to load");
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                if (closeAction != null) closeAction.run();
                else listener.onRendererGone(view);
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(
                    WebView view,
                    ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams) {
                boolean opened = fileChooser.show(filePathCallback, fileChooserParams);
                if (!opened) listener.onMessage("Cannot open file picker");
                return opened;
            }

            @Override
            public boolean onCreateWindow(
                    WebView view,
                    boolean isDialog,
                    boolean isUserGesture,
                    Message resultMsg) {
                if (!isUserGesture || !(resultMsg.obj instanceof WebView.WebViewTransport transport)) {
                    return false;
                }

                Dialog dialog = new Dialog(
                        activity,
                        android.R.style.Theme_DeviceDefault_Light_NoActionBar_Fullscreen);
                WebView popup = new WebView(activity);
                configure(
                        activity,
                        popup,
                        profileName,
                        fileChooser,
                        downloads,
                        listener,
                        dialog::dismiss,
                        false);
                dialog.setContentView(popup, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
                dialog.setOnDismissListener(ignored -> destroyPopup(popup));
                dialog.show();

                transport.setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public void onCloseWindow(WebView window) {
                if (closeAction != null) closeAction.run();
                else super.onCloseWindow(window);
            }
        });

        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                downloads.enqueue(url, userAgent, contentDisposition, mimeType));
    }

    private static boolean openExternal(ComponentActivity activity, Uri uri, Listener listener) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception error) {
            listener.onMessage("Cannot open external link");
        }
        return true;
    }

    private static void destroyPopup(WebView webView) {
        webView.stopLoading();
        webView.setWebChromeClient(null);
        webView.setWebViewClient(null);
        webView.removeAllViews();
        webView.destroy();
    }
}
