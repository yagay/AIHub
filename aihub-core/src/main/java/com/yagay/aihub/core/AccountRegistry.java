package com.yagay.aihub.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class AccountRegistry {
    private final Map<String, AiAccount> byId = new LinkedHashMap<>();

    public synchronized void register(AiAccount account) {
        byId.put(account.id(), account);
    }

    public synchronized boolean contains(String id) {
        return byId.containsKey(id);
    }

    public synchronized AiAccount require(String id) {
        AiAccount account = byId.get(id);
        if (account == null) throw new IllegalArgumentException("Unknown account: " + id);
        return account;
    }

    public synchronized List<AiAccount> all() {
        return new ArrayList<>(byId.values());
    }

    public synchronized List<AiAccount> forProvider(String providerId) {
        List<AiAccount> out = new ArrayList<>();
        for (AiAccount account : byId.values()) {
            if (account.providerId().equals(providerId)) out.add(account);
        }
        return out;
    }

    public synchronized boolean remove(String id) {
        return byId.remove(id) != null;
    }
}
