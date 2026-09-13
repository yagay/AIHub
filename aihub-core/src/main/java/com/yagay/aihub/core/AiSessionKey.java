package com.yagay.aihub.core;

import java.util.Objects;

/** One long-lived browser session per AI provider. */
public record AiSessionKey(String providerId) {
    public AiSessionKey {
        Objects.requireNonNull(providerId);
        if (providerId.isBlank()) throw new IllegalArgumentException("providerId is required");
    }

    @Override
    public String toString() { return providerId; }
}
