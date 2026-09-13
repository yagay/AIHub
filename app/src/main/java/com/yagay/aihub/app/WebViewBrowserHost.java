package com.yagay.aihub.app;

import android.Manifest;
import android.app.Activity;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Environment;
import android.os.Message;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.yagay.aihub.android.AiHubBrowserHost;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Browser implementation backed by Android System WebView.
 *
 * The application compiles only AIHub code. Chromium/WebView itself is supplied and updated by the
 * device, which keeps GitHub builds small while retaining a modern Chromium rendering engine.
 */
public final class WebViewBrowserHost implements AiHubBrowserHost {
    private static final int REQUEST_FILE_CHOOSER = 4107;
    private static final int REQUEST_WEB_MEDIA_PERMISSION = 4108;
    private static final int REQUEST_GEOLOCATION_PERMISSION = 4109;
    private static final int TOP_UI_DP = 94;
    private static final int BOTTOM_UI_DP = 60;

    private final MainActivity activity;
    private final FrameLayout root;
    private final Map<String, WebView> pages = new LinkedHashMap<>();
    private final Map<String, WebView> popups = new LinkedHashMap<>();
    private final Map<String, String> homeUrls = new LinkedHashMap<>();

    private String currentProviderId;
    private ValueCallback<Uri[]> pendingFileChooser;
    private PermissionRequest pendingWebPermission;
    private String[] pendingWebResources;
    private GeolocationPermissions.Callback pendingGeolocationCallback;
    private String pendingGeolocationOrigin;

    public WebViewBrowserHost(MainActivity activity, FrameLayout root) {
        this.activity = activity;
        this.root = root;
        CookieManager.getInstance().setAcceptCookie(true);
    }

    @Override
    public Activity activity() {
        return activity;
    }

    @Override
    public ViewGroup overlayRoot() {
        return root;
    }

    @Override
    public @Nullable View chromeControlContainer() {
        return null;
    }

    @Override
    public void openOrSelectProvider(String providerId, String homeUrl) {
        homeUrls.put(providerId, homeUrl);
        WebView page = pages.get(providerId);
        if (page == null) {
            page = createWebView(providerId, false);
            pages.put(providerId, page);
            root.addView(page, 0, pageLayoutParams());
            page.loadUrl(homeUrl);
        }
        selectProvider(providerId);
    }

    @Override
    public void selectProvider(String providerId) {
        if (!pages.containsKey(providerId)) return;
        currentProviderId = providerId;
        for (Map.Entry<String, WebView> item : pages.entrySet()) {
            item.getValue().setVisibility(item.getKey().equals(providerId) && !popups.containsKey(providerId)
                    ? View.VISIBLE : View.GONE);
        }
        for (Map.Entry<String, WebView> item : popups.entrySet()) {
            item.getValue().setVisibility(item.getKey().equals(providerId) ? View.VISIBLE : View.GONE);
        }
        WebView current = current();
        if (current != null) current.onResume();
    }

    @Override
    public void closeProvider(String providerId) {
        WebView popup = popups.remove(providerId);
        destroyView(popup);
        WebView page = pages.remove(providerId);
        destroyView(page);
        homeUrls.remove(providerId);
        if (providerId.equals(currentProviderId)) currentProviderId = null;
    }

    @Override
    public void evaluateJavaScript(String script, @Nullable ValueCallback<String> callback) {
        WebView page = current();
        if (page == null) {
            if (callback != null) callback.onReceiveValue("null");
            return;
        }
        page.evaluateJavascript(script, callback);
    }

    @Override
    public void back() {
        WebView page = current();
        if (page != null && page.canGoBack()) page.goBack();
    }

    public boolean canGoBack() {
        WebView page = current();
        return page != null && page.canGoBack();
    }

    @Override
    public void forward() {
        WebView page = current();
        if (page != null && page.canGoForward()) page.goForward();
    }

    @Override
    public void reload() {
        WebView page = current();
        if (page != null) page.reload();
    }

    @Override
    public void stopLoading() {
        WebView page = current();
        if (page != null) page.stopLoading();
    }

    @Override
    public String currentUrl() {
        WebView page = current();
        String value = page == null ? null : page.getUrl();
        return value == null ? "" : value;
    }

    @Override
    public boolean isLoading() {
        WebView page = current();
        return page != null && page.getProgress() < 100;
    }

    @Override
    public int loadProgress() {
        WebView page = current();
        return page == null ? 0 : page.getProgress();
    }

    @Override
    public void onAiHubError(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }

    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        if (requestCode != REQUEST_FILE_CHOOSER) return;
        ValueCallback<Uri[]> callback = pendingFileChooser;
        pendingFileChooser = null;
        if (callback != null) {
            callback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data));
        }
    }

    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_WEB_MEDIA_PERMISSION) {
            PermissionRequest request = pendingWebPermission;
            String[] resources = pendingWebResources;
            pendingWebPermission = null;
            pendingWebResources = null;
            if (request == null || resources == null) return;
            if (allGranted(grantResults)) request.grant(resources);
            else request.deny();
            return;
        }

        if (requestCode == REQUEST_GEOLOCATION_PERMISSION) {
            GeolocationPermissions.Callback callback = pendingGeolocationCallback;
            String origin = pendingGeolocationOrigin;
            pendingGeolocationCallback = null;
            pendingGeolocationOrigin = null;
            if (callback != null && origin != null) {
                callback.invoke(origin, allGranted(grantResults), false);
            }
        }
    }

    public void destroy() {
        if (pendingFileChooser != null) pendingFileChooser.onReceiveValue(null);
        pendingFileChooser = null;
        if (pendingWebPermission != null) pendingWebPermission.deny();
        pendingWebPermission = null;
        if (pendingGeolocationCallback != null && pendingGeolocationOrigin != null) {
            pendingGeolocationCallback.invoke(pendingGeolocationOrigin, false, false);
        }
        pendingGeolocationCallback = null;
        pendingGeolocationOrigin = null;

        for (WebView popup : new ArrayList<>(popups.values())) destroyView(popup);
        for (WebView page : new ArrayList<>(pages.values())) destroyView(page);
        popups.clear();
        pages.clear();
    }

    private WebView createWebView(String providerId, boolean popup) {
        WebView webView = new WebView(activity);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setLoadsImagesAutomatically(true);
        settings.setSupportZoom(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(true);
        settings.setSafeBrowsingEnabled(true);

        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true);
        webView.setWebViewClient(new AiHubWebViewClient(providerId, popup));
        webView.setWebChromeClient(new AiHubChromeClient(providerId));
        webView.setDownloadListener((url, userAgent, contentDisposition, mimeType, contentLength) ->
                download(url, userAgent, contentDisposition, mimeType));
        webView.setVisibility(View.GONE);
        return webView;
    }

    private final class AiHubWebViewClient extends WebViewClient {
        private final String providerId;
        private final boolean popup;

        AiHubWebViewClient(String providerId, boolean popup) {
            this.providerId = providerId;
            this.popup = popup;
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return handleNavigation(view, request.getUrl());
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            return handleNavigation(view, Uri.parse(url));
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            if (popup) {
                WebView known = popups.get(providerId);
                if (known == view) popups.remove(providerId);
                destroyView(view);
                WebView main = pages.get(providerId);
                if (main != null && providerId.equals(currentProviderId)) main.setVisibility(View.VISIBLE);
                onAiHubError("Web page process restarted");
                return true;
            }

            WebView known = pages.get(providerId);
            if (known == view) pages.remove(providerId);
            destroyView(view);
            String home = homeUrls.get(providerId);
            if (home != null) {
                WebView replacement = createWebView(providerId, false);
                pages.put(providerId, replacement);
                root.addView(replacement, 0, pageLayoutParams());
                if (providerId.equals(currentProviderId) && !popups.containsKey(providerId)) {
                    replacement.setVisibility(View.VISIBLE);
                }
                replacement.loadUrl(home);
            }
            onAiHubError("Web page process restarted");
            return true;
        }
    }

    private final class AiHubChromeClient extends WebChromeClient {
        private final String providerId;

        AiHubChromeClient(String providerId) {
            this.providerId = providerId;
        }

        @Override
        public boolean onShowFileChooser(
                WebView webView,
                ValueCallback<Uri[]> filePathCallback,
                FileChooserParams fileChooserParams) {
            if (pendingFileChooser != null) pendingFileChooser.onReceiveValue(null);
            pendingFileChooser = filePathCallback;
            try {
                activity.startActivityForResult(fileChooserParams.createIntent(), REQUEST_FILE_CHOOSER);
                return true;
            } catch (ActivityNotFoundException error) {
                pendingFileChooser = null;
                filePathCallback.onReceiveValue(null);
                onAiHubError("No file picker is available");
                return true;
            }
        }

        @Override
        public void onPermissionRequest(PermissionRequest request) {
            activity.runOnUiThread(() -> requestWebPermission(request));
        }

        @Override
        public void onPermissionRequestCanceled(PermissionRequest request) {
            if (request == pendingWebPermission) {
                pendingWebPermission = null;
                pendingWebResources = null;
            }
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(
                String origin,
                GeolocationPermissions.Callback callback) {
            if (activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                callback.invoke(origin, true, false);
                return;
            }
            pendingGeolocationOrigin = origin;
            pendingGeolocationCallback = callback;
            activity.requestPermissions(new String[]{
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
            }, REQUEST_GEOLOCATION_PERMISSION);
        }

        @Override
        public boolean onCreateWindow(
                WebView view,
                boolean isDialog,
                boolean isUserGesture,
                Message resultMsg) {
            WebView oldPopup = popups.remove(providerId);
            destroyView(oldPopup);

            WebView popup = createWebView(providerId, true);
            popups.put(providerId, popup);
            root.addView(popup, 0, pageLayoutParams());
            view.setVisibility(View.GONE);
            if (providerId.equals(currentProviderId)) popup.setVisibility(View.VISIBLE);

            WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
            transport.setWebView(popup);
            resultMsg.sendToTarget();
            return true;
        }

        @Override
        public void onCloseWindow(WebView window) {
            String owner = null;
            for (Map.Entry<String, WebView> item : popups.entrySet()) {
                if (item.getValue() == window) {
                    owner = item.getKey();
                    break;
                }
            }
            if (owner == null) return;
            popups.remove(owner);
            destroyView(window);
            WebView main = pages.get(owner);
            if (main != null && owner.equals(currentProviderId)) main.setVisibility(View.VISIBLE);
        }
    }

    private void requestWebPermission(PermissionRequest request) {
        List<String> webResources = new ArrayList<>();
        List<String> androidPermissions = new ArrayList<>();
        for (String resource : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) {
                webResources.add(resource);
                androidPermissions.add(Manifest.permission.CAMERA);
            } else if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(resource)) {
                webResources.add(resource);
                androidPermissions.add(Manifest.permission.RECORD_AUDIO);
            }
        }
        if (webResources.isEmpty()) {
            request.deny();
            return;
        }

        List<String> missing = new ArrayList<>();
        for (String permission : androidPermissions) {
            if (activity.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED
                    && !missing.contains(permission)) {
                missing.add(permission);
            }
        }
        String[] grantResources = webResources.toArray(String[]::new);
        if (missing.isEmpty()) {
            request.grant(grantResources);
            return;
        }

        if (pendingWebPermission != null) pendingWebPermission.deny();
        pendingWebPermission = request;
        pendingWebResources = grantResources;
        activity.requestPermissions(missing.toArray(String[]::new), REQUEST_WEB_MEDIA_PERMISSION);
    }

    private boolean handleNavigation(WebView source, Uri uri) {
        String scheme = uri.getScheme();
        if (scheme == null || scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")
                || scheme.equalsIgnoreCase("about") || scheme.equalsIgnoreCase("data")
                || scheme.equalsIgnoreCase("blob")) {
            return false;
        }
        try {
            Intent intent;
            if (scheme.equalsIgnoreCase("intent")) {
                intent = Intent.parseUri(uri.toString(), Intent.URI_INTENT_SCHEME);
            } else {
                intent = new Intent(Intent.ACTION_VIEW, uri);
            }
            activity.startActivity(intent);
        } catch (Exception error) {
            onAiHubError("Cannot open external link");
        }
        return true;
    }

    private void download(String url, String userAgent, String contentDisposition, String mimeType) {
        try {
            String fileName = URLUtil.guessFileName(url, contentDisposition, mimeType);
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setTitle(fileName);
            request.setMimeType(mimeType);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName);
            request.setAllowedOverMetered(true);
            request.setAllowedOverRoaming(true);
            if (userAgent != null && !userAgent.isBlank()) request.addRequestHeader("User-Agent", userAgent);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null && !cookies.isBlank()) request.addRequestHeader("Cookie", cookies);
            DownloadManager manager = (DownloadManager) activity.getSystemService(Context.DOWNLOAD_SERVICE);
            manager.enqueue(request);
            Toast.makeText(activity, "Downloading " + fileName, Toast.LENGTH_SHORT).show();
        } catch (Exception error) {
            onAiHubError("Download failed: " + error.getMessage());
        }
    }

    private WebView current() {
        if (currentProviderId == null) return null;
        WebView popup = popups.get(currentProviderId);
        return popup != null ? popup : pages.get(currentProviderId);
    }

    private FrameLayout.LayoutParams pageLayoutParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        params.topMargin = dp(TOP_UI_DP);
        params.bottomMargin = dp(BOTTOM_UI_DP);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private void destroyView(@Nullable WebView view) {
        if (view == null) return;
        if (view.getParent() instanceof ViewGroup parent) parent.removeView(view);
        view.stopLoading();
        view.setWebChromeClient(null);
        view.setWebViewClient(null);
        view.removeAllViews();
        view.destroy();
    }

    private static boolean allGranted(int[] results) {
        if (results.length == 0) return false;
        for (int result : results) {
            if (result != PackageManager.PERMISSION_GRANTED) return false;
        }
        return true;
    }
}
