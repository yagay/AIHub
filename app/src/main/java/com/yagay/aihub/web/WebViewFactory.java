package com.yagay.aihub.web;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

/** Creates every WebView with the same security/settings policy. */
public final class WebViewFactory {
    public interface Listener {
        void onPageFinished(WebView view, String url);
        void onRendererGone(WebView view);
        void onMessage(String message);
    }

    private WebViewFactory() {}

    public static WebView create(Activity activity, String profileName, Listener listener) {
        WebView webView = new WebView(activity);

        // AndroidX requires profile assignment before navigation/evaluateJavascript and before normal
        // WebView use. This keeps every provider/account session isolated when supported by runtime.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE)) {
            WebViewCompat.setProfile(webView, profileName);
        } else {
            listener.onMessage("This Android WebView does not support isolated multi-account profiles; sessions will share website data.");
        }

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSafeBrowsingEnabled(true);

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
                try {
                    activity.startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (Exception error) {
                    listener.onMessage("Cannot open external link");
                }
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                listener.onPageFinished(view, url);
            }

            @Override
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                listener.onRendererGone(view);
                return true;
            }
        });

        return webView;
    }
}
