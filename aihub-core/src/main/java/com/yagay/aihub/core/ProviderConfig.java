package com.yagay.aihub.core;

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
        capabilities = Set.copyOf(capabilities == null ? Set.of() : capabilities);
        inputSelectors = List.copyOf(inputSelectors == null ? List.of() : inputSelectors);
        sendSelectors = List.copyOf(sendSelectors == null ? List.of() : sendSelectors);
        newChatSelectors = List.copyOf(newChatSelectors == null ? List.of() : newChatSelectors);
        stopSelectors = List.copyOf(stopSelectors == null ? List.of() : stopSelectors);
    }
}
