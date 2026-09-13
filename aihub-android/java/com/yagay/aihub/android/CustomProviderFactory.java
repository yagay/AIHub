package com.yagay.aihub.android;

import com.yagay.aihub.core.AiCapability;
import com.yagay.aihub.core.ProviderConfig;
import com.yagay.aihub.core.provider.ProviderRegistry;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Creates and persists user-added AI websites without any Chromium-specific code. */
public final class CustomProviderFactory {
    private CustomProviderFactory() {}

    public static ProviderConfig create(
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
                Set.of(
                        AiCapability.TEXT,
                        AiCapability.FILE_UPLOAD,
                        AiCapability.NEW_CHAT,
                        AiCapability.STOP),
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
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("Invalid URL");
        }
        return uri.toString();
    }
}
