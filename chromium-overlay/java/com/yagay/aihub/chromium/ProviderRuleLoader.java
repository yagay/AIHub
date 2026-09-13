package com.yagay.aihub.chromium;

import android.content.Context;
import android.content.res.AssetManager;

import com.yagay.aihub.core.provider.BuiltinProviders;
import com.yagay.aihub.core.provider.ProviderRegistry;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads provider differences from JSON assets, custom providers and verified signed overrides. */
public final class ProviderRuleLoader {
    private static final String ASSET_DIR = "aihub/providers";

    private final Context context;
    private final AiHubStateStore stateStore;
    private final List<String> warnings = new ArrayList<>();

    public ProviderRuleLoader(Context context) {
        this(context, new AiHubStateStore(context));
    }

    public ProviderRuleLoader(Context context, AiHubStateStore stateStore) {
        this.context = context.getApplicationContext();
        this.stateStore = stateStore;
    }

    public ProviderRegistry load() {
        warnings.clear();
        Map<String, ProviderRuleCodec.Decoded> merged = new LinkedHashMap<>();

        // Lowest priority: APK-bundled rules.
        try {
            AssetManager assets = context.getAssets();
            String[] names = assets.list(ASSET_DIR);
            if (names != null) {
                Arrays.sort(names);
                for (String name : names) {
                    if (!name.endsWith(".json")) continue;
                    try {
                        ProviderRuleCodec.Decoded rule = ProviderRuleCodec.decode(
                                readAsset(assets, ASSET_DIR + "/" + name));
                        merged.put(rule.provider().id(), rule);
                    } catch (Exception error) {
                        warnings.add(name + ": " + error.getMessage());
                    }
                }
            }
        } catch (Exception error) {
            warnings.add("provider asset list: " + error.getMessage());
        }

        // User-created providers replace a matching bundled id (normally custom ids are unique).
        for (String customRule : stateStore.loadCustomProviderRules()) {
            try {
                ProviderRuleCodec.Decoded rule = ProviderRuleCodec.decode(customRule);
                merged.put(rule.provider().id(), rule);
            } catch (Exception error) {
                warnings.add("custom provider: " + error.getMessage());
            }
        }

        // Highest priority: rules that passed the configured Ed25519 verifier.
        String remoteEnvelope = stateStore.remoteRuleBundle();
        if (remoteEnvelope != null && !remoteEnvelope.isBlank()) {
            try {
                SignedProviderRuleBundle signed = new SignedProviderRuleBundle(context, stateStore);
                for (ProviderRuleCodec.Decoded rule : signed.loadInstalled()) {
                    merged.put(rule.provider().id(), rule);
                }
            } catch (Exception error) {
                warnings.add("signed provider rules ignored: " + error.getMessage());
            }
        }

        List<ProviderRuleCodec.Decoded> decoded = new ArrayList<>(merged.values());
        decoded.sort(Comparator
                .comparingInt(ProviderRuleCodec.Decoded::order)
                .thenComparing(item -> item.provider().displayName(), String.CASE_INSENSITIVE_ORDER));

        ProviderRegistry registry = new ProviderRegistry();
        for (ProviderRuleCodec.Decoded item : decoded) registry.register(item.provider());
        if (registry.all().isEmpty()) {
            warnings.add("No valid provider rules loaded; using built-in fallback registry");
            return BuiltinProviders.createDefaultRegistry();
        }
        return registry;
    }

    public List<String> warnings() { return List.copyOf(warnings); }

    private static String readAsset(AssetManager assets, String path) throws Exception {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                assets.open(path), StandardCharsets.UTF_8))) {
            StringBuilder out = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) out.append(line).append('\n');
            return out.toString();
        }
    }
}
