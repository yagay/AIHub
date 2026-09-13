package com.yagay.aihub.core;

import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record ProviderConfig(
        String id,
        String displayName,
        String homeUrl,
        Set<AiCapability> capabilities,
        List<String> inputSelectors,
        List<String> sendSelectors,
        List<String> newChatSelectors,
        List<String> stopSelectors) {

    public ProviderConfig {
        Objects.requireNonNull(id);
        Objects.requireNonNull(displayName);
        Objects.requireNonNull(homeUrl);
        id = id.trim();
        displayName = displayName.trim();
        homeUrl = homeUrl.trim();
        if (!id.matches("[A-Za-z0-9_.-]+")) {
            throw new IllegalArgumentException("Invalid provider id: " + id);
        }
        if (displayName.isEmpty()) throw new IllegalArgumentException("Provider displayName is empty");
        validateHomeUrl(homeUrl);
        capabilities = Set.copyOf(capabilities == null ? Set.of() : capabilities);
        inputSelectors = cleanSelectors(inputSelectors);
        sendSelectors = cleanSelectors(sendSelectors);
        newChatSelectors = cleanSelectors(newChatSelectors);
        stopSelectors = cleanSelectors(stopSelectors);
    }

    private static void validateHomeUrl(String value) {
        try {
            URI uri = URI.create(value);
            String scheme = uri.getScheme();
            if (!("https".equalsIgnoreCase(scheme) || "http".equalsIgnoreCase(scheme))) {
                throw new IllegalArgumentException("Provider URL must use http/https: " + value);
            }
            if (uri.getHost() == null || uri.getHost().isBlank()) {
                throw new IllegalArgumentException("Provider URL has no host: " + value);
            }
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid provider URL: " + value, error);
        }
    }

    private static List<String> cleanSelectors(List<String> values) {
        if (values == null || values.isEmpty()) return List.of();
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .distinct()
                .toList();
    }
}
