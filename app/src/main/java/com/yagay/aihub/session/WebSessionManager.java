package com.yagay.aihub.session;

import android.app.Activity;
import android.view.View;
import android.webkit.WebView;
import android.widget.FrameLayout;

import com.yagay.aihub.model.AccountProfile;
import com.yagay.aihub.provider.AiProviderAdapter;
import com.yagay.aihub.web.WebViewFactory;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Owns retained WebViews. Provider switching never recreates an existing provider/account session.
 * All UI commands flow through the current provider adapter.
 */
public final class WebSessionManager {
    public interface Events {
        void onMessage(String message);
        void onPageReady();
    }

    private static final class Session {
        final SessionKey key;
        final AiProviderAdapter adapter;
        final AccountProfile account;
        final String profileName;
        WebView webView;

        Session(SessionKey key, AiProviderAdapter adapter, AccountProfile account, String profileName) {
            this.key = key;
            this.adapter = adapter;
            this.account = account;
            this.profileName = profileName;
        }
    }

    private final Activity activity;
    private final FrameLayout container;
    private final Events events;
    private final Map<SessionKey, Session> sessions = new LinkedHashMap<>();
    private SessionKey currentKey;
    private boolean appModeEnabled = true;

    public WebSessionManager(Activity activity, FrameLayout container, Events events) {
        this.activity = activity;
        this.container = container;
        this.events = events;
    }

    public void switchTo(AiProviderAdapter adapter, AccountProfile account, String profileName) {
        SessionKey key = new SessionKey(adapter.spec().id(), account.id());
        Session session = sessions.get(key);
        if (session == null) {
            session = new Session(key, adapter, account, profileName);
            sessions.put(key, session);
            createWebView(session);
            session.webView.loadUrl(adapter.spec().homeUrl());
        }
        currentKey = key;
        for (Session item : sessions.values()) {
            item.webView.setVisibility(item.key.equals(key) ? View.VISIBLE : View.GONE);
            if (item.key.equals(key)) item.webView.onResume();
            else item.webView.onPause();
        }
        applyAppMode(session);
    }

    public void send(String text) {
        Session session = current();
        if (session == null || text == null || text.isBlank()) return;
        session.webView.evaluateJavascript(session.adapter.sendScript(text), null);
    }

    public void newChat() {
        Session session = current();
        if (session != null) session.webView.evaluateJavascript(session.adapter.newChatScript(), null);
    }

    public void stopGeneration() {
        Session session = current();
        if (session != null) session.webView.evaluateJavascript(session.adapter.stopScript(), null);
    }

    public void reload() {
        Session session = current();
        if (session != null) session.webView.reload();
    }

    public boolean goBack() {
        Session session = current();
        if (session == null || !session.webView.canGoBack()) return false;
        session.webView.goBack();
        return true;
    }

    public void setAppModeEnabled(boolean enabled) {
        appModeEnabled = enabled;
        Session session = current();
        if (session != null) applyAppMode(session);
    }

    public boolean isAppModeEnabled() {
        return appModeEnabled;
    }

    public void destroy() {
        for (Session session : sessions.values()) destroyWebView(session.webView);
        sessions.clear();
        currentKey = null;
    }

    private void createWebView(Session session) {
        WebView webView = WebViewFactory.create(activity, session.profileName, new WebViewFactory.Listener() {
            @Override
            public void onPageFinished(WebView view, String url) {
                if (view != session.webView) return;
                applyAppMode(session);
                if (session.key.equals(currentKey)) events.onPageReady();
            }

            @Override
            public void onRendererGone(WebView view) {
                if (view != session.webView) return;
                boolean wasCurrent = session.key.equals(currentKey);
                container.removeView(view);
                destroyWebView(view);
                session.webView = null;
                createWebView(session);
                session.webView.loadUrl(session.adapter.spec().homeUrl());
                session.webView.setVisibility(wasCurrent ? View.VISIBLE : View.GONE);
                events.onMessage("Web page process restarted");
            }

            @Override
            public void onMessage(String message) {
                events.onMessage(message);
            }
        });
        session.webView = webView;
        webView.setVisibility(View.GONE);
        container.addView(webView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void applyAppMode(Session session) {
        if (session.webView == null) return;
        session.webView.evaluateJavascript(session.adapter.appModeScript(appModeEnabled), null);
    }

    private Session current() {
        return currentKey == null ? null : sessions.get(currentKey);
    }

    private static void destroyWebView(WebView webView) {
        if (webView == null) return;
        webView.stopLoading();
        webView.setWebChromeClient(null);
        webView.setWebViewClient(null);
        webView.removeAllViews();
        webView.destroy();
    }
}
