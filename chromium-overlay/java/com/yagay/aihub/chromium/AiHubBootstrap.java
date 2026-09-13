package com.yagay.aihub.chromium;

import android.content.Context;

import com.yagay.aihub.core.AiCapability;
import com.yagay.aihub.core.ProviderConfig;
import com.yagay.aihub.core.SessionManager;
import com.yagay.aihub.core.command.AiCommandBus;
import com.yagay.aihub.core.provider.ProviderRegistry;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Builds the provider-only object graph used by UI and external command entry points. */
public final class AiHubBootstrap {
    private AiHubBootstrap() {}

    public static final class Graph {
        public final ProviderRegistry providers;
        public final SessionManager sessions;
        public final AiCommandBus commands;
        public final List<String> providerWarnings;

        private Graph(
                ProviderRegistry providers,
                SessionManager sessions,
                AiCommandBus commands,
                List<String> providerWarnings) {
            this.providers = providers;
            this.sessions = sessions;
            this.commands = commands;
            this.providerWarnings = List.copyOf(providerWarnings);
        }
    }

    public static Graph create(Context context, AiHubStateStore stateStore, WebEngineSessionRuntime runtime) {
        ProviderRuleLoader ruleLoader = new ProviderRuleLoader(context, stateStore);
        ProviderRegistry providers = ruleLoader.load();
        SessionManager sessions = new SessionManager(providers, runtime);
        return new Graph(providers, sessions, new AiCommandBus(sessions), ruleLoader.warnings());
    }

    public static ProviderConfig addCustomProvider(
            ProviderRegistry providers,
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
}
