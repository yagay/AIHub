package com.yagay.aihub.android;

import com.yagay.aihub.core.provider.BuiltinProviders;
import com.yagay.aihub.core.provider.ProviderRegistry;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads built-in, user-created and verified signed provider rules without Chromium dependencies. */
public final class ProviderRuleLoader {
    private final AiHubStateStore stateStore;
    private final List<String> warnings = new ArrayList<>();

    public ProviderRuleLoader(AiHubStateStore stateStore) {
        this.stateStore = stateStore;
    }

    public ProviderRegistry load() {
        warnings.clear();
        Map<String, ProviderRuleCodec.Decoded> merged = new LinkedHashMap<>();

        // Lowest priority: JSON rules embedded by apply_chrome_overlay.py. A non-Chromium unit
        // environment has no generated class and therefore falls back to BuiltinProviders below.
        for (String raw : AiHubEmbeddedRules.builtinRules()) {
            try {
                ProviderRuleCodec.Decoded rule = ProviderRuleCodec.decode(raw);
                merged.put(rule.provider().id(), rule);
            } catch (Exception error) {
                warnings.add("built-in provider: " + message(error));
            }
        }

        // User-created providers replace a matching built-in id (normally custom ids are unique).
        for (String customRule : stateStore.loadCustomProviderRules()) {
            try {
                ProviderRuleCodec.Decoded rule = ProviderRuleCodec.decode(customRule);
                merged.put(rule.provider().id(), rule);
            } catch (Exception error) {
                warnings.add("custom provider: " + message(error));
            }
        }

        // Highest priority: rules that passed the build-embedded Ed25519 public key.
        String remoteEnvelope = stateStore.remoteRuleBundle();
        if (remoteEnvelope != null && !remoteEnvelope.isBlank()) {
            try {
                SignedProviderRuleBundle signed = new SignedProviderRuleBundle(stateStore);
                for (ProviderRuleCodec.Decoded rule : signed.loadInstalled()) {
                    merged.put(rule.provider().id(), rule);
                }
            } catch (Exception error) {
                warnings.add("signed provider rules ignored: " + message(error));
            }
        }

        if (merged.isEmpty()) {
            warnings.add("No generated provider rules found; using built-in Java fallback registry");
            return BuiltinProviders.createDefaultRegistry();
        }

        List<ProviderRuleCodec.Decoded> decoded = new ArrayList<>(merged.values());
        decoded.sort(Comparator
                .comparingInt(ProviderRuleCodec.Decoded::order)
                .thenComparing(item -> item.provider().displayName(), String.CASE_INSENSITIVE_ORDER));

        ProviderRegistry registry = new ProviderRegistry();
        for (ProviderRuleCodec.Decoded item : decoded) registry.register(item.provider());
        return registry;
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value == null || value.isBlank() ? error.getClass().getSimpleName() : value;
    }
}
