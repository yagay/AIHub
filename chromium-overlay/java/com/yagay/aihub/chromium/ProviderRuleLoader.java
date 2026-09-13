package com.yagay.aihub.chromium;

import android.content.Context;
import android.content.res.AssetManager;

import com.yagay.aihub.core.ProviderConfig;
import com.yagay.aihub.core.provider.BuiltinProviders;
import com.yagay.aihub.core.provider.ProviderRegistry;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Loads provider differences from JSON assets plus user-added custom provider rules. */
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
        ProviderRegistry registry = new ProviderRegistry();
        warnings.clear();
        List<ProviderRuleCodec.Decoded> decoded = new ArrayList<>();

        try {
            AssetManager assets = context.getAssets();
            String[] names = assets.list(ASSET_DIR);
            if (names != null) {
                Arrays.sort(names);
                for (String name : names) {
                    if (!name.endsWith(".json")) continue;
                    try {
                        decoded.add(ProviderRuleCodec.decode(readAsset(assets, ASSET_DIR + "/" + name)));
                    } catch (Exception error) {
                        warnings.add(name + ": " + error.getMessage());
                    }
                }
            }
        } catch (Exception error) {
            warnings.add("provider asset list: " + error.getMessage());
        }

        for (String customRule : stateStore.loadCustomProviderRules()) {
            try {
                decoded.add(ProviderRuleCodec.decode(customRule));
            } catch (Exception error) {
                warnings.add("custom provider: " + error.getMessage());
            }
        }

        decoded.sort(Comparator
                .comparingInt(ProviderRuleCodec.Decoded::order)
                .thenComparing(item -> item.provider().displayName(), String.CASE_INSENSITIVE_ORDER));
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
