package com.yagay.aihub.core.provider;

import com.yagay.aihub.core.ProviderConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ProviderRegistry {
    private final Map<String, ProviderConfig> providers = new LinkedHashMap<>();

    public synchronized void register(ProviderConfig provider) {
        providers.put(provider.id(), provider);
    }

    public synchronized ProviderConfig require(String id) {
        ProviderConfig value = providers.get(id);
        if (value == null) throw new IllegalArgumentException("Unknown provider: " + id);
        return value;
    }

    public synchronized List<ProviderConfig> all() {
        return new ArrayList<>(providers.values());
    }

    public synchronized boolean contains(String id) {
        return providers.containsKey(id);
    }
}
