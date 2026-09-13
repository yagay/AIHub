package com.yagay.aihub.core;

import com.yagay.aihub.core.provider.ProviderRegistry;
import com.yagay.aihub.core.runtime.SessionRuntime;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One long-lived session per AI provider. Switching providers activates an existing retained browser
 * surface whenever possible instead of rebuilding login/page state.
 */
public final class SessionManager {
    private final ProviderRegistry providers;
    private final SessionRuntime runtime;
    private final Map<AiSessionKey, AiSession> sessions = new LinkedHashMap<>();
    private AiSessionKey current;

    public SessionManager(ProviderRegistry providers, SessionRuntime runtime) {
        this.providers = Objects.requireNonNull(providers);
        this.runtime = Objects.requireNonNull(runtime);
    }

    public synchronized AiSession activate(String providerId) {
        ProviderConfig provider = providers.require(providerId);
        AiSessionKey key = new AiSessionKey(provider.id());
        AiSession session = sessions.get(key);
        if (session == null) {
            session = new AiSession(key, provider.homeUrl());
            sessions.put(key, session);
            runtime.open(key, provider);
        }
        runtime.activate(key);
        session.markActivated();
        session.setState(SessionState.READY);
        current = key;
        return session;
    }

    public synchronized AiSession current() {
        if (current == null) throw new IllegalStateException("No active AI session");
        return sessions.get(current);
    }

    public synchronized AiSession currentOrNull() {
        return current == null ? null : sessions.get(current);
    }

    public synchronized AiSessionKey currentKey() { return current().key(); }

    public synchronized AiSession switchProvider(String providerId) { return activate(providerId); }
    public synchronized AiSession nextProvider() { return moveProvider(1); }
    public synchronized AiSession previousProvider() { return moveProvider(-1); }

    public synchronized void sendText(String text) {
        if (text == null || text.isBlank()) return;
        runtime.sendText(currentKey(), text);
    }

    public synchronized void newChat() { runtime.newChat(currentKey()); }
    public synchronized void stop() { runtime.stop(currentKey()); }

    public synchronized void attach(List<String> uris) {
        runtime.attach(currentKey(), List.copyOf(uris == null ? List.of() : uris));
    }

    public synchronized void attachAndSend(List<String> uris, String text) {
        List<String> safeUris = List.copyOf(uris == null ? List.of() : uris);
        if (safeUris.isEmpty()) {
            sendText(text);
            return;
        }
        if (text == null || text.isBlank()) {
            attach(safeUris);
            return;
        }
        runtime.attachAndSend(currentKey(), safeUris, text);
    }

    public synchronized void back() { runtime.back(currentKey()); }
    public synchronized void forward() { runtime.forward(currentKey()); }
    public synchronized void reload() { runtime.reload(currentKey()); }

    public synchronized void closeCurrent() {
        AiSessionKey key = currentKey();
        runtime.close(key);
        sessions.remove(key);
        current = null;
    }

    public synchronized Map<AiSessionKey, AiSession> snapshot() { return Map.copyOf(sessions); }

    private AiSession moveProvider(int delta) {
        List<ProviderConfig> all = providers.all();
        if (all.isEmpty()) throw new IllegalStateException("No providers registered");
        String currentProvider = current != null ? current.providerId() : all.get(0).id();
        int index = 0;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(currentProvider)) {
                index = i;
                break;
            }
        }
        return activate(all.get(Math.floorMod(index + delta, all.size())).id());
    }
}
