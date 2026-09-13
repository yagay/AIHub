package com.yagay.aihub.core;

import java.util.Objects;

public record AiSessionKey(String providerId, String accountId) {
    public AiSessionKey {
        Objects.requireNonNull(providerId);
        Objects.requireNonNull(accountId);
    }

    @Override
    public String toString() {
        return providerId + ":" + accountId;
    }
}
