package com.yagay.aihub.chromium;

import com.yagay.aihub.core.AccountRegistry;
import com.yagay.aihub.core.AiAccount;
import com.yagay.aihub.core.ProviderConfig;
import com.yagay.aihub.core.SessionManager;
import com.yagay.aihub.core.WorkspaceRegistry;
import com.yagay.aihub.core.command.AiCommandBus;
import com.yagay.aihub.core.provider.BuiltinProviders;
import com.yagay.aihub.core.provider.ProviderRegistry;

import java.util.List;
import java.util.Locale;
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

        private Graph(
                ProviderRegistry providers,
                AccountRegistry accounts,
                WorkspaceRegistry workspaces,
                SessionManager sessions,
                AiCommandBus commands) {
            this.providers = providers;
            this.accounts = accounts;
            this.workspaces = workspaces;
            this.sessions = sessions;
            this.commands = commands;
        }
    }

    public static Graph create(AiHubStateStore stateStore, WebEngineSessionRuntime runtime) {
        ProviderRegistry providers = BuiltinProviders.createDefaultRegistry();
        AccountRegistry accounts = new AccountRegistry();
        WorkspaceRegistry workspaces = new WorkspaceRegistry();

        for (AiAccount account : stateStore.loadAccounts()) {
            if (providers.contains(account.providerId())) accounts.register(account);
        }
        ensureDefaultAccounts(providers, accounts);
        stateStore.saveAccounts(accounts.all());

        SessionManager sessions = new SessionManager(providers, accounts, workspaces, runtime);
        return new Graph(providers, accounts, workspaces, sessions, new AiCommandBus(sessions));
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
        String display = label == null || label.isBlank() ? "Account " + (accounts.forProvider(providerId).size() + 1) : label.trim();
        AiAccount account = new AiAccount(id, providerId, display, profileName);
        accounts.register(account);
        stateStore.saveAccounts(accounts.all());
        return account;
    }

    private static void ensureDefaultAccounts(ProviderRegistry providers, AccountRegistry accounts) {
        for (ProviderConfig provider : providers.all()) {
            List<AiAccount> existing = accounts.forProvider(provider.id());
            if (!existing.isEmpty()) continue;
            String profileName = "aihub_" + provider.id().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_") + "_default";
            accounts.register(new AiAccount(
                    provider.id() + ":default",
                    provider.id(),
                    "Default",
                    profileName));
        }
    }
}
