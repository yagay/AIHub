package com.yagay.aihub.chromium;

import android.content.Context;

import com.yagay.aihub.core.AccountRegistry;
import com.yagay.aihub.core.AiAccount;
import com.yagay.aihub.core.AiCapability;
import com.yagay.aihub.core.ProviderConfig;
import com.yagay.aihub.core.SessionManager;
import com.yagay.aihub.core.WorkspaceRegistry;
import com.yagay.aihub.core.command.AiCommandBus;
import com.yagay.aihub.core.provider.ProviderRegistry;

import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/** Builds the shared object graph used by UI and external command entry points. */
public final class AiHubBootstrap {
    private AiHubBootstrap() {}

    public static final class Graph {
        public final ProviderRegistry providers;
        public final AccountRegistry accounts;
        public final WorkspaceRegistry workspaces;
        public final SessionManager sessions;
        public final AiCommandBus commands;
        public final List<String> providerWarnings;

        private Graph(
                ProviderRegistry providers,
                AccountRegistry accounts,
                WorkspaceRegistry workspaces,
                SessionManager sessions,
                AiCommandBus commands,
                List<String> providerWarnings) {
            this.providers = providers;
            this.accounts = accounts;
            this.workspaces = workspaces;
            this.sessions = sessions;
            this.commands = commands;
            this.providerWarnings = List.copyOf(providerWarnings);
        }
    }

    public static Graph create(Context context, AiHubStateStore stateStore, WebEngineSessionRuntime runtime) {
        ProviderRuleLoader ruleLoader = new ProviderRuleLoader(context, stateStore);
        ProviderRegistry providers = ruleLoader.load();
        AccountRegistry accounts = new AccountRegistry();
        WorkspaceRegistry workspaces = new WorkspaceRegistry();

        for (AiAccount account : stateStore.loadAccounts()) {
            if (providers.contains(account.providerId())) accounts.register(account);
        }
        ensureDefaultAccounts(providers, accounts);
        stateStore.saveAccounts(accounts.all());
        stateStore.loadWorkspaces().forEach(workspaces::register);

        SessionManager sessions = new SessionManager(providers, accounts, workspaces, runtime);
        return new Graph(providers, accounts, workspaces, sessions,
                new AiCommandBus(sessions), ruleLoader.warnings());
    }

    public static AiAccount addAccount(
            ProviderRegistry providers,
            AccountRegistry accounts,
            AiHubStateStore stateStore,
            String providerId,
            String label) {
        providers.require(providerId);
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String safeProvider = providerId.replaceAll("[^A-Za-z0-9_]", "_");
        String id = providerId + ":" + uuid;
        String profileName = "aihub_" + safeProvider + "_" + uuid;
        String display = label == null || label.isBlank()
                ? "Account " + (accounts.forProvider(providerId).size() + 1)
                : label.trim();
        AiAccount account = new AiAccount(id, providerId, display, profileName);
        accounts.register(account);
        stateStore.saveAccounts(accounts.all());
        return account;
    }

    public static ProviderConfig addCustomProvider(
            ProviderRegistry providers,
            AccountRegistry accounts,
            AiHubStateStore stateStore,
            String displayName,
            String url) {
        String name = displayName == null ? "" : displayName.trim();
        String normalizedUrl = normalizeUrl(url);
        if (name.isEmpty()) throw new IllegalArgumentException("AI name is required");
        String id = "custom_" + UUID.randomUUID().toString().replace("-", "");
        ProviderConfig provider = new ProviderConfig(
                id,
                name,
                normalizedUrl,
                Set.of(AiCapability.TEXT, AiCapability.FILE_UPLOAD, AiCapability.NEW_CHAT, AiCapability.STOP),
                List.of(), List.of(), List.of(), List.of());
        providers.register(provider);
        stateStore.saveCustomProvider(provider);
        addAccount(providers, accounts, stateStore, provider.id(), "Default");
        return provider;
    }

    private static String normalizeUrl(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("URL is required");
        String value = raw.trim();
        if (!value.contains("://")) value = "https://" + value;
        URI uri = URI.create(value);
        String scheme = uri.getScheme();
        if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("Only http/https URLs are supported");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) throw new IllegalArgumentException("Invalid URL");
        return uri.toString();
    }

    private static void ensureDefaultAccounts(ProviderRegistry providers, AccountRegistry accounts) {
        for (ProviderConfig provider : providers.all()) {
            List<AiAccount> existing = accounts.forProvider(provider.id());
            if (!existing.isEmpty()) continue;
            String profileName = "aihub_"
                    + provider.id().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_")
                    + "_default";
            accounts.register(new AiAccount(
                    provider.id() + ":default", provider.id(), "Default", profileName));
        }
    }
}
