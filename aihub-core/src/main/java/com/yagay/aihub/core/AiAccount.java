package com.yagay.aihub.core;

import java.util.Objects;

/**
 * A logical login for one provider. profileName points to the isolated Chromium/WebEngine
 * browser profile that owns cookies, local storage and other site data.
 */
public record AiAccount(
        String id,
        String providerId,
        String label,
        String profileName) {

    public AiAccount {
        Objects.requireNonNull(id);
        Objects.requireNonNull(providerId);
        Objects.requireNonNull(label);
        Objects.requireNonNull(profileName);
        if (!profileName.matches("[A-Za-z0-9_]+")) {
            throw new IllegalArgumentException("profileName must contain only A-Z, a-z, 0-9 or _");
        }
    }
}
