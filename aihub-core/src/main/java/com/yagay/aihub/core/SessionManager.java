package com.yagay.aihub.core;

import com.yagay.aihub.core.provider.ProviderRegistry;
import com.yagay.aihub.core.runtime.SessionRuntime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Single switching entry point for provider, account and workspace switching. */
public final class SessionManager {
    private final ProviderRegistry providers;
    private final AccountRegistry accounts;
    private final WorkspaceRegistry workspaces;
    private final SessionRuntime runtime;
    private final Map<AiSessionKey, AiSession> sessions = new LinkedHashMap<>();
    private final Map<String, String> lastAccountByProvider = new LinkedHashMap<>();
    private AiSessionKey current;
    private String activeWorkspaceId;

    public SessionManager(
            ProviderRegistry providers,
            AccountRegistry accounts,
            WorkspaceRegistry workspaces,
            SessionRuntime runtime) {
        this.providers = Objects.requireNonNull(providers);
        this.accounts = Objects.requireNonNull(accounts);
        this.workspaces = Objects.requireNonNull(workspaces);
        this.runtime = Objects.requireNonNull(runtime);
    }

    public synchronized AiSession activate(String providerId, String accountId, String workspaceId) {
        AiSessionKey key = resolve(providerId, accountId, workspaceId);
        ProviderConfig provider = providers.require(key.providerId());
        AiAccount account = accounts.require(key.accountId());
        if (!account.providerId().equals(provider.id())) {
            throw new IllegalArgumentException("Account " + account.id() + " does not belong to " + provider.id());
        }
        AiSession session = sessions.get(key);
        if (session == null) {
            session = new AiSession(key, provider.homeUrl());
            sessions.put(key, session);
            runtime.open(key, provider, account);
        }
        runtime.activate(key);
        session.markActivated();
        session.setState(SessionState.READY);
        current = key;
        lastAccountByProvider.put(key.providerId(), key.accountId());
        if (workspaceId != null && !workspaceId.isBlank()) activeWorkspaceId = workspaceId;
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
    public synchronized String activeWorkspaceId() { return activeWorkspaceId; }

    public synchronized String preferredAccountId(String providerId) {
        providers.require(providerId);
        String id = lastAccountByProvider.get(providerId);
        if (validAccountForProvider(id, providerId)) return id;
        List<AiAccount> candidates = accounts.forProvider(providerId);
        if (candidates.isEmpty()) throw new IllegalStateException("No account registered for provider: " + providerId);
        return candidates.get(0).id();
    }

    public synchronized AiAccount renameAccount(String accountId, String newLabel) {
        AiAccount old = accounts.require(accountId);
        String label = newLabel == null ? "" : newLabel.trim();
        if (label.isEmpty()) throw new IllegalArgumentException("Account name is required");
        AiAccount renamed = new AiAccount(old.id(), old.providerId(), label, old.profileName());
        accounts.register(renamed);
        return renamed;
    }

    public synchronized AiSession removeAccount(String accountId) {
        AiAccount removed = accounts.require(accountId);
        List<AiAccount> providerAccounts = accounts.forProvider(removed.providerId());
        if (providerAccounts.size() <= 1) {
            throw new IllegalStateException("Keep at least one account for " + removed.providerId());
        }

        AiSessionKey removedKey = new AiSessionKey(removed.providerId(), removed.id());
        AiSession existing = sessions.remove(removedKey);
        if (existing != null) runtime.close(removedKey);
        accounts.remove(removed.id());
        if (removed.id().equals(lastAccountByProvider.get(removed.providerId()))) {
            lastAccountByProvider.remove(removed.providerId());
        }

        String replacement = preferredAccountId(removed.providerId());
        for (AiWorkspace workspace : workspaces.all()) {
            if (!removed.id().equals(workspace.accountFor(removed.providerId()))) continue;
            Map<String, String> mapping = new LinkedHashMap<>(workspace.providerAccounts());
            mapping.put(removed.providerId(), replacement);
            workspaces.register(new AiWorkspace(workspace.id(), workspace.label(), mapping));
        }

        if (removedKey.equals(current)) {
            current = null;
            return activate(removed.providerId(), replacement, activeWorkspaceId);
        }
        return currentOrNull();
    }

    public synchronized AiSession switchProvider(String providerId) {
        return activate(providerId, null, activeWorkspaceId);
    }

    public synchronized AiSession switchAccount(String accountId) {
        return switchAccount(null, accountId);
    }

    /** Explicit account selection always exits workspace mode and validates an optional provider hint. */
    public synchronized AiSession switchAccount(String providerId, String accountId) {
        AiAccount account = accounts.require(accountId);
        if (providerId != null && !providerId.isBlank() && !providerId.equals(account.providerId())) {
            throw new IllegalArgumentException(
                    "Account " + account.id() + " does not belong to " + providerId);
        }
        activeWorkspaceId = null;
        return activate(account.providerId(), account.id(), null);
    }

    public synchronized AiSession switchWorkspace(String workspaceId) {
        String providerId = current != null ? current.providerId() : firstProviderId();
        workspaces.require(workspaceId);
        activeWorkspaceId = workspaceId;
        return activate(providerId, null, workspaceId);
    }

    public synchronized AiSession switchWorkspace(String workspaceId, String providerId) {
        workspaces.require(workspaceId);
        activeWorkspaceId = workspaceId;
        return activate(providerId, null, workspaceId);
    }

    public synchronized void clearWorkspace() { activeWorkspaceId = null; }

    public synchronized AiWorkspace renameWorkspace(String workspaceId, String newLabel) {
        AiWorkspace old = workspaces.require(workspaceId);
        String label = newLabel == null ? "" : newLabel.trim();
        if (label.isEmpty()) throw new IllegalArgumentException("Workspace name is required");
        AiWorkspace renamed = new AiWorkspace(old.id(), label, old.providerAccounts());
        workspaces.register(renamed);
        return renamed;
    }

    public synchronized boolean removeWorkspace(String workspaceId) {
        workspaces.require(workspaceId);
        boolean active = workspaceId.equals(activeWorkspaceId);
        boolean removed = workspaces.remove(workspaceId);
        if (active) activeWorkspaceId = null;
        return removed;
    }

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
        String now = current != null ? current.providerId() : all.get(0).id();
        int index = 0;
        for (int i = 0; i < all.size(); i++) {
            if (all.get(i).id().equals(now)) { index = i; break; }
        }
        int next = Math.floorMod(index + delta, all.size());
        return activate(all.get(next).id(), null, activeWorkspaceId);
    }

    private AiSessionKey resolve(String providerId, String accountId, String workspaceId) {
        String p = providerId;
        if (p == null || p.isBlank()) p = current != null ? current.providerId() : firstProviderId();
        providers.require(p);

        String a = accountId;
        if (!validAccountForProvider(a, p)) a = null;
        if (a == null && workspaceId != null && !workspaceId.isBlank()) {
            String workspaceAccount = workspaces.require(workspaceId).accountFor(p);
            if (validAccountForProvider(workspaceAccount, p)) a = workspaceAccount;
        }
        if (a == null) {
            String previous = lastAccountByProvider.get(p);
            if (validAccountForProvider(previous, p)) a = previous;
        }
        if (a == null) a = preferredAccountId(p);
        return new AiSessionKey(p, a);
    }

    private boolean validAccountForProvider(String accountId, String providerId) {
        if (accountId == null || accountId.isBlank() || !accounts.contains(accountId)) return false;
        return providerId.equals(accounts.require(accountId).providerId());
    }

    private String firstProviderId() {
        List<ProviderConfig> all = providers.all();
        if (all.isEmpty()) throw new IllegalStateException("No providers registered");
        return all.get(0).id();
    }
}
