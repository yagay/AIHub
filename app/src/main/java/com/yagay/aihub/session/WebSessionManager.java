package com.yagay.aihub.session;

import android.content.Intent;
import android.net.Uri;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.activity.ComponentActivity;

import com.yagay.aihub.model.AccountProfile;
import com.yagay.aihub.model.ChatMessage;
import com.yagay.aihub.provider.AiProviderAdapter;
import com.yagay.aihub.web.GeckoEngine;
import com.yagay.aihub.web.GeckoFilePicker;

import org.json.JSONArray;
import org.json.JSONObject;
import org.mozilla.geckoview.AllowOrDeny;
import org.mozilla.geckoview.GeckoResult;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.GeckoSessionSettings;
import org.mozilla.geckoview.GeckoView;
import org.mozilla.geckoview.WebExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Owns retained Gecko sessions and the shared native conversation mirror. */
public final class WebSessionManager {
    public interface Events {
        void onMessage(String message);
        void onPageReady();
        void onConversationChanged(List<ChatMessage> messages);
        void onAppModeChanged(boolean enabled);
    }

    private static final class Session {
        final SessionKey key;
        final AiProviderAdapter adapter;
        final AccountProfile account;
        final String contextId;
        GeckoSession geckoSession;
        WebExtension.Port bridgePort;
        String lastUrl;
        boolean canGoBack;
        boolean appCapable;
        List<ChatMessage> lastMessages = List.of();

        Session(SessionKey key, AiProviderAdapter adapter, AccountProfile account, String contextId) {
            this.key = key;
            this.adapter = adapter;
            this.account = account;
            this.contextId = contextId;
        }
    }

    private final ComponentActivity activity;
    private final Events events;
    private final Map<SessionKey, Session> sessions = new LinkedHashMap<>();
    private final GeckoRuntime runtime;
    private final GeckoView geckoView;
    private final GeckoFilePicker filePicker;

    private SessionKey currentKey;
    private boolean appModeRequested;
    private boolean appModeEnabled;
    private long requestSequence;

    public WebSessionManager(ComponentActivity activity, FrameLayout container, Events events) {
        this.activity = activity;
        this.events = events;
        this.runtime = GeckoEngine.runtime(activity);
        this.filePicker = new GeckoFilePicker(activity);
        this.geckoView = new GeckoView(activity);
        container.removeAllViews();
        container.addView(geckoView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
    }

    public void switchTo(AiProviderAdapter adapter, AccountProfile account, String contextId) {
        appModeRequested = false;
        setActualAppMode(false);
        SessionKey key = new SessionKey(adapter.spec().id(), account.id());
        Session session = sessions.get(key);
        if (session == null) {
            session = new Session(key, adapter, account, contextId);
            sessions.put(key, session);
            createSession(session);
            session.geckoSession.loadUri(adapter.spec().homeUrl());
        }
        currentKey = key;
        attachToView(session.geckoSession);
        events.onConversationChanged(session.lastMessages);
    }

    public void send(String text) {
        Session session = current();
        if (session == null || text == null || text.isBlank()) return;
        if (!requireNativeReady(session)) return;

        List<ChatMessage> optimistic = new ArrayList<>(session.lastMessages);
        optimistic.add(new ChatMessage("user", text));
        session.lastMessages = List.copyOf(optimistic);
        events.onConversationChanged(session.lastMessages);
        post(session, session.adapter.sendCommand(text));
    }

    public void newChat() {
        Session session = current();
        if (session == null || !requireNativeReady(session)) return;
        session.lastMessages = List.of();
        events.onConversationChanged(session.lastMessages);
        post(session, session.adapter.newChatCommand());
    }

    public void stopGeneration() {
        Session session = current();
        if (session != null && requireNativeReady(session)) post(session, session.adapter.stopCommand());
    }

    public void requestAttachment() {
        Session session = current();
        if (session != null && requireNativeReady(session)) post(session, session.adapter.attachmentCommand());
    }

    public void reload() {
        Session session = current();
        if (session != null) session.geckoSession.reload();
    }

    public boolean goBack() {
        Session session = current();
        if (session == null || !session.canGoBack) return false;
        session.geckoSession.goBack();
        return true;
    }

    public void setAppModeEnabled(boolean enabled) {
        if (!enabled) {
            appModeRequested = false;
            setActualAppMode(false);
            return;
        }

        Session session = current();
        if (session == null) return;
        if (!isTrusted(session)) {
            appModeRequested = false;
            setActualAppMode(false);
            events.onMessage("Finish sign-in in WEB mode first.");
            return;
        }

        appModeRequested = true;
        if (session.bridgePort == null) {
            setActualAppMode(false);
            events.onMessage("The page is still loading. APP mode will remain off until the page bridge is ready.");
            return;
        }
        requestProbe(session);
    }

    public boolean isAppModeEnabled() {
        return appModeEnabled;
    }

    public void destroy() {
        filePicker.destroy();
        if (geckoView.getSession() != null) geckoView.releaseSession();
        for (Session session : sessions.values()) destroySession(session);
        sessions.clear();
        currentKey = null;
        appModeRequested = false;
        appModeEnabled = false;
    }

    private void createSession(Session holder) {
        GeckoSessionSettings settings = new GeckoSessionSettings.Builder()
                .contextId(holder.contextId)
                .useTrackingProtection(false)
                .build();
        GeckoSession session = new GeckoSession(settings);
        holder.geckoSession = session;

        session.setContentDelegate(new GeckoSession.ContentDelegate() {
            @Override
            public void onCrash(GeckoSession crashed) {
                if (crashed != holder.geckoSession) return;
                boolean selected = holder.key.equals(currentKey);
                String target = holder.lastUrl == null ? holder.adapter.spec().homeUrl() : holder.lastUrl;
                if (geckoView.getSession() == crashed) geckoView.releaseSession();
                destroySession(holder);
                createSession(holder);
                holder.geckoSession.loadUri(target);
                if (selected) {
                    appModeRequested = false;
                    setActualAppMode(false);
                    attachToView(holder.geckoSession);
                }
                events.onMessage("Browser process restarted");
            }
        });

        session.setNavigationDelegate(new GeckoSession.NavigationDelegate() {
            @Override
            public void onLocationChange(
                    GeckoSession changed,
                    String url,
                    List<GeckoSession.PermissionDelegate.ContentPermission> permissions,
                    Boolean hasUserGesture) {
                holder.lastUrl = url;
                holder.appCapable = false;
                if (holder.key.equals(currentKey)) {
                    if (!holder.adapter.spec().ownsUrl(url)) appModeRequested = false;
                    setActualAppMode(false);
                }
            }

            @Override
            public void onCanGoBack(GeckoSession changed, boolean canGoBack) {
                holder.canGoBack = canGoBack;
            }

            @Override
            public GeckoResult<AllowOrDeny> onLoadRequest(
                    GeckoSession changed,
                    GeckoSession.NavigationDelegate.LoadRequest request) {
                Uri uri = safeUri(request.uri);
                if (uri != null && request.target == GeckoSession.NavigationDelegate.TARGET_WINDOW_NEW) {
                    if (isWebScheme(uri)) changed.loadUri(request.uri);
                    else openExternal(uri);
                    return GeckoResult.deny();
                }
                if (uri != null && !isWebScheme(uri)) {
                    openExternal(uri);
                    return GeckoResult.deny();
                }
                return null;
            }
        });

        session.setProgressDelegate(new GeckoSession.ProgressDelegate() {
            @Override
            public void onPageStop(GeckoSession loaded, boolean success) {
                if (!holder.key.equals(currentKey)) return;
                events.onPageReady();
                if (success && appModeRequested && holder.bridgePort != null && isTrusted(holder)) {
                    requestProbe(holder);
                }
            }
        });

        session.setPromptDelegate(new GeckoSession.PromptDelegate() {
            @Override
            public GeckoResult<GeckoSession.PromptDelegate.PromptResponse> onFilePrompt(
                    GeckoSession source,
                    GeckoSession.PromptDelegate.FilePrompt prompt) {
                GeckoResult<GeckoSession.PromptDelegate.PromptResponse> result = new GeckoResult<>();
                boolean multiple = prompt.type == GeckoSession.PromptDelegate.FilePrompt.Type.MULTIPLE;
                boolean opened = filePicker.show(prompt.mimeTypes, multiple, uris -> {
                    if (uris == null || uris.length == 0) {
                        result.complete(prompt.dismiss());
                    } else if (uris.length == 1) {
                        result.complete(prompt.confirm(activity.getApplicationContext(), uris[0]));
                    } else {
                        result.complete(prompt.confirm(activity.getApplicationContext(), uris));
                    }
                });
                if (!opened) result.complete(prompt.dismiss());
                return result;
            }

            @Override
            public GeckoResult<GeckoSession.PromptDelegate.PromptResponse> onPopupPrompt(
                    GeckoSession source,
                    GeckoSession.PromptDelegate.PopupPrompt prompt) {
                return GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW));
            }
        });

        session.setPermissionDelegate(new GeckoSession.PermissionDelegate() {
            @Override
            public GeckoResult<Integer> onContentPermissionRequest(
                    GeckoSession source,
                    GeckoSession.PermissionDelegate.ContentPermission permission) {
                if (permission.permission == GeckoSession.PermissionDelegate.PERMISSION_STORAGE_ACCESS
                        || permission.permission == GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE) {
                    return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW);
                }
                return GeckoResult.fromValue(GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY);
            }
        });

        session.open(runtime);
        attachBridge(holder);
    }

    private void attachBridge(Session holder) {
        GeckoEngine.attachBridge(
                activity,
                holder.geckoSession,
                new WebExtension.MessageDelegate() {
                    @Override
                    public void onConnect(WebExtension.Port port) {
                        if (port.sender.session != holder.geckoSession || !port.sender.isTopLevel()) {
                            port.disconnect();
                            return;
                        }
                        if (port.sender.url != null && !port.sender.url.isBlank()) {
                            holder.lastUrl = port.sender.url;
                        }
                        holder.bridgePort = port;
                        port.setDelegate(new WebExtension.PortDelegate() {
                            @Override
                            public void onPortMessage(Object message, WebExtension.Port source) {
                                if (source != holder.bridgePort || !(message instanceof JSONObject json)) return;
                                handleBridgeMessage(holder, json);
                            }

                            @Override
                            public void onDisconnect(WebExtension.Port source) {
                                if (source == holder.bridgePort) holder.bridgePort = null;
                            }
                        });
                        if (holder.key.equals(currentKey) && appModeRequested && isTrusted(holder)) {
                            requestProbe(holder);
                        }
                    }
                },
                () -> {},
                error -> events.onMessage("AIHub browser bridge failed to load"));
    }

    private void handleBridgeMessage(Session holder, JSONObject message) {
        String type = message.optString("type", "");
        String url = message.optString("url", "");
        if (!url.isBlank()) holder.lastUrl = url;

        if (type.equals("ready") || type.equals("location")) {
            if (holder.key.equals(currentKey) && appModeRequested && isTrusted(holder)) requestProbe(holder);
            return;
        }

        if (type.equals("dirty")) {
            if (holder.key.equals(currentKey) && appModeEnabled) requestSync(holder);
            return;
        }

        if (!type.equals("response")) return;
        String action = message.optString("action", "");
        if (action.equals("probe")) {
            boolean capable = message.optBoolean("ok", false) && message.optBoolean("composer", false);
            holder.appCapable = capable;
            if (!holder.key.equals(currentKey) || !appModeRequested) return;
            if (capable) {
                setActualAppMode(true);
                requestSync(holder);
            } else {
                appModeRequested = false;
                setActualAppMode(false);
                events.onMessage("APP mode is not available on this page. Stay in WEB mode for sign-in or unsupported pages.");
            }
            return;
        }

        if (action.equals("sync")) {
            JSONArray messages = message.optJSONArray("messages");
            if (messages == null) return;
            List<ChatMessage> parsed = parseMessages(messages);
            if (!parsed.equals(holder.lastMessages)) {
                holder.lastMessages = parsed;
                if (holder.key.equals(currentKey) && appModeEnabled) events.onConversationChanged(parsed);
            }
            return;
        }

        if (!message.optBoolean("ok", false) && holder.key.equals(currentKey)) {
            appModeRequested = false;
            events.onMessage("The website control changed. Use WEB mode and update this provider's selectors.");
            setActualAppMode(false);
        }
    }

    private void requestProbe(Session session) {
        post(session, session.adapter.probeCommand());
    }

    private void requestSync(Session session) {
        if (!appModeEnabled || session.bridgePort == null) return;
        post(session, session.adapter.syncCommand());
    }

    private void post(Session session, JSONObject command) {
        if (session.bridgePort == null) return;
        try {
            command.put("requestId", Long.toString(++requestSequence));
            session.bridgePort.postMessage(command);
        } catch (Exception error) {
            events.onMessage("Could not send command to web page");
        }
    }

    private boolean requireNativeReady(Session session) {
        if (appModeEnabled && session.appCapable && session.bridgePort != null && isTrusted(session)) return true;
        events.onMessage("APP mode is not ready. Switch to WEB mode and finish loading/sign-in first.");
        return false;
    }

    private boolean isTrusted(Session session) {
        return session.adapter.spec().ownsUrl(session.lastUrl);
    }

    private void setActualAppMode(boolean enabled) {
        if (appModeEnabled == enabled) return;
        appModeEnabled = enabled;
        events.onAppModeChanged(enabled);
        if (enabled) {
            Session session = current();
            if (session != null) events.onConversationChanged(session.lastMessages);
        }
    }

    private void attachToView(GeckoSession session) {
        GeckoSession attached = geckoView.getSession();
        if (attached == session) return;
        if (attached != null) geckoView.releaseSession();
        geckoView.setSession(session);
    }

    private Session current() {
        return currentKey == null ? null : sessions.get(currentKey);
    }

    private static List<ChatMessage> parseMessages(JSONArray array) {
        List<ChatMessage> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            String role = item.optString("role", "assistant");
            String text = item.optString("text", "").trim();
            if (!text.isEmpty()) out.add(new ChatMessage(role, text));
        }
        return List.copyOf(out);
    }

    private void destroySession(Session holder) {
        if (holder.bridgePort != null) {
            holder.bridgePort.disconnect();
            holder.bridgePort = null;
        }
        if (holder.geckoSession != null) {
            holder.geckoSession.close();
            holder.geckoSession = null;
        }
    }

    private void openExternal(Uri uri) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, uri));
        } catch (Exception error) {
            events.onMessage("Cannot open external link");
        }
    }

    private static Uri safeUri(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Uri.parse(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isWebScheme(Uri uri) {
        String scheme = uri.getScheme();
        return scheme == null || scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https")
                || scheme.equalsIgnoreCase("about") || scheme.equalsIgnoreCase("data")
                || scheme.equalsIgnoreCase("blob");
    }
}
