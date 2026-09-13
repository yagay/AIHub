package com.yagay.aihub.session;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.WebView;
import android.widget.FrameLayout;

import androidx.activity.ComponentActivity;

import com.yagay.aihub.model.AccountProfile;
import com.yagay.aihub.model.ChatMessage;
import com.yagay.aihub.provider.AiProviderAdapter;
import com.yagay.aihub.web.DownloadHandler;
import com.yagay.aihub.web.FileChooserCoordinator;
import com.yagay.aihub.web.WebViewFactory;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owns retained provider/account WebViews and the shared native conversation mirror. */
public final class WebSessionManager {
    public interface Events {
        void onMessage(String message);
        void onPageReady();
        void onConversationChanged(List<ChatMessage> messages);
    }

    private static final long SYNC_INTERVAL_MS = 900L;

    private static final class Session {
        final SessionKey key;
        final AiProviderAdapter adapter;
        final AccountProfile account;
        final String profileName;
        WebView webView;
        String lastUrl;
        List<ChatMessage> lastMessages = List.of();

        Session(SessionKey key, AiProviderAdapter adapter, AccountProfile account, String profileName) {
            this.key = key;
            this.adapter = adapter;
            this.account = account;
            this.profileName = profileName;
        }
    }

    private final ComponentActivity activity;
    private final FrameLayout container;
    private final Events events;
    private final Map<SessionKey, Session> sessions = new LinkedHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final FileChooserCoordinator fileChooser;
    private final DownloadHandler downloads;
    private final Runnable syncRunnable = this::syncCurrent;

    private SessionKey currentKey;
    private boolean appModeEnabled = true;

    public WebSessionManager(ComponentActivity activity, FrameLayout container, Events events) {
        this.activity = activity;
        this.container = container;
        this.events = events;
        this.fileChooser = new FileChooserCoordinator(activity);
        this.downloads = new DownloadHandler(activity, events::onMessage);
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
            boolean selected = item.key.equals(key);
            item.webView.setVisibility(selected ? View.VISIBLE : View.GONE);
            if (selected) item.webView.onResume();
            else item.webView.onPause();
        }
        if (appModeEnabled) events.onConversationChanged(session.lastMessages);
        scheduleSync(0L);
    }

    public void send(String text) {
        Session session = current();
        if (session == null || text == null || text.isBlank() || !requireTrusted(session)) return;

        List<ChatMessage> optimistic = new ArrayList<>(session.lastMessages);
        optimistic.add(new ChatMessage("user", text));
        session.lastMessages = List.copyOf(optimistic);
        if (appModeEnabled) events.onConversationChanged(session.lastMessages);

        session.webView.evaluateJavascript(session.adapter.sendScript(text), result -> {
            String value = decodeJsValue(result);
            if (value != null && value.contains("\"ok\":false")) {
                events.onMessage("Could not find the website message box. Switch to WEB mode and retry.");
            }
            scheduleSync(250L);
        });
    }

    public void newChat() {
        Session session = current();
        if (session == null || !requireTrusted(session)) return;
        session.lastMessages = List.of();
        if (appModeEnabled) events.onConversationChanged(session.lastMessages);
        session.webView.evaluateJavascript(session.adapter.newChatScript(), ignored -> scheduleSync(350L));
    }

    public void stopGeneration() {
        Session session = current();
        if (session != null && requireTrusted(session)) {
            session.webView.evaluateJavascript(session.adapter.stopScript(), ignored -> scheduleSync(150L));
        }
    }

    public void requestAttachment() {
        Session session = current();
        if (session == null || !requireTrusted(session)) return;
        session.webView.evaluateJavascript(session.adapter.attachmentScript(), result -> {
            String value = decodeJsValue(result);
            if (value == null || value.contains("missing")) {
                events.onMessage("Attachment control was not found. Switch to WEB mode for this site.");
            }
        });
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
        handler.removeCallbacks(syncRunnable);
        Session session = current();
        if (enabled && session != null) {
            events.onConversationChanged(session.lastMessages);
            scheduleSync(0L);
        }
    }

    public boolean isAppModeEnabled() {
        return appModeEnabled;
    }

    public void destroy() {
        handler.removeCallbacks(syncRunnable);
        fileChooser.destroy();
        for (Session session : sessions.values()) destroyWebView(session.webView);
        sessions.clear();
        currentKey = null;
    }

    private void createWebView(Session session) {
        WebView webView = WebViewFactory.create(
                activity,
                session.profileName,
                fileChooser,
                downloads,
                new WebViewFactory.Listener() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        if (view != session.webView) return;
                        session.lastUrl = url;
                        if (session.key.equals(currentKey)) {
                            events.onPageReady();
                            scheduleSync(100L);
                        }
                    }

                    @Override
                    public void onRendererGone(WebView view) {
                        if (view != session.webView) return;
                        boolean wasCurrent = session.key.equals(currentKey);
                        String target = session.lastUrl == null ? session.adapter.spec().homeUrl() : session.lastUrl;
                        container.removeView(view);
                        destroyWebView(view);
                        session.webView = null;
                        createWebView(session);
                        session.webView.loadUrl(target);
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

    private void syncCurrent() {
        handler.removeCallbacks(syncRunnable);
        if (!appModeEnabled) return;
        Session session = current();
        if (session == null || session.webView == null) return;
        if (!isTrusted(session)) {
            scheduleSync(1200L);
            return;
        }

        session.webView.evaluateJavascript(session.adapter.conversationScript(), result -> {
            if (!appModeEnabled || session != current()) return;
            List<ChatMessage> parsed = parseConversation(result);
            if (parsed != null && !parsed.equals(session.lastMessages)) {
                session.lastMessages = parsed;
                events.onConversationChanged(parsed);
            }
            scheduleSync(SYNC_INTERVAL_MS);
        });
    }

    private void scheduleSync(long delayMs) {
        handler.removeCallbacks(syncRunnable);
        if (!appModeEnabled || current() == null) return;
        handler.postDelayed(syncRunnable, Math.max(0L, delayMs));
    }

    private boolean requireTrusted(Session session) {
        if (isTrusted(session)) return true;
        events.onMessage("Finish sign-in in WEB mode before using native controls.");
        return false;
    }

    private boolean isTrusted(Session session) {
        return session.webView != null && session.adapter.spec().ownsUrl(session.webView.getUrl());
    }

    private Session current() {
        return currentKey == null ? null : sessions.get(currentKey);
    }

    private static List<ChatMessage> parseConversation(String rawResult) {
        String json = decodeJsValue(rawResult);
        if (json == null) return null;
        try {
            JSONArray array = new JSONArray(json);
            List<ChatMessage> out = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) continue;
                String role = item.optString("role", "assistant");
                String text = item.optString("text", "").trim();
                if (!text.isEmpty()) out.add(new ChatMessage(role, text));
            }
            return List.copyOf(out);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String decodeJsValue(String raw) {
        if (raw == null || raw.equals("null") || raw.isBlank()) return null;
        try {
            Object value = new JSONTokener(raw).nextValue();
            return value instanceof String ? (String) value : String.valueOf(value);
        } catch (Exception ignored) {
            return null;
        }
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
