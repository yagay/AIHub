package com.yagay.aihub.core;

import java.util.Map;
import java.util.Objects;

/** Maps a provider to the preferred account in a workspace such as Personal or Work. */
public record AiWorkspace(String id, String label, Map<String, String> providerAccounts) {
    public AiWorkspace {
        Objects.requireNonNull(id);
        Objects.requireNonNull(label);
        providerAccounts = Map.copyOf(providerAccounts == null ? Map.of() : providerAccounts);
    }

    public String accountFor(String providerId) {
        return providerAccounts.get(providerId);
    }
}
