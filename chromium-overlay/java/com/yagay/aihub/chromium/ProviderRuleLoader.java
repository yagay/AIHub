package com.yagay.aihub.chromium;

import android.content.Context;
import android.content.res.AssetManager;

import com.yagay.aihub.core.AiCapability;
import com.yagay.aihub.core.ProviderConfig;
import com.yagay.aihub.core.provider.BuiltinProviders;
import com.yagay.aihub.core.provider.ProviderRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Loads provider differences from assets. The app core does not know ChatGPT/Claude/Gemini DOM
 * details; adding or repairing a provider should normally mean editing one JSON rule file.
 */
public final class ProviderRuleLoader {
    private static final String ASSET_DIR = "aihub/providers";

    private final Context context;
    private final List<String> warnings = new ArrayList<>();

    public ProviderRuleLoader(Context context) {
        this.context = context.getApplicationContext();
    }

    public ProviderRegistry load() {
        ProviderRegistry registry = new ProviderRegistry();
        warnings.clear();
        try {
            AssetManager assets = context.getAssets();
            String[] names = assets.list(ASSET_DIR);
            if (names != null) {
                Arrays.sort(names);
                for (String name : names) {
                    if (!name.endsWith(".json")) continue;
                    try {
                        registry.register(parse(readAsset(assets, ASSET_DIR + "/" + name)));
                    } catch (Exception error) {
                        warnings.add(name + ": " + error.getMessage());
                    }
                }
            }
        } catch (Exception error) {
            warnings.add("provider asset list: " + error.getMessage());
        }

        if (registry.all().isEmpty()) {
            warnings.add("No valid provider rules loaded; using built-in fallback registry");
            return BuiltinProviders.createDefaultRegistry();
        }
        return registry;
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    private static ProviderConfig parse(String raw) throws Exception {
        JSONObject root = new JSONObject(raw);
        JSONObject selectors = root.optJSONObject("selectors");
        if (selectors == null) selectors = new JSONObject();

        Set<AiCapability> capabilities = new LinkedHashSet<>();
        JSONArray caps = root.optJSONArray("capabilities");
        if (caps != null) {
            for (int i = 0; i < caps.length(); i++) {
                capabilities.add(AiCapability.valueOf(caps.getString(i)));
            }
        }

        return new ProviderConfig(
                required(root, "id"),
                required(root, "displayName"),
                required(root, "homeUrl"),
                capabilities,
                stringList(selectors.optJSONArray("input")),
                stringList(selectors.optJSONArray("send")),
                stringList(selectors.optJSONArray("newChat")),
                stringList(selectors.optJSONArray("stop")));
    }

    private static String required(JSONObject object, String key) throws Exception {
        String value = object.getString(key).trim();
        if (value.isEmpty()) throw new IllegalArgumentException(key + " is empty");
        return value;
    }

    private static List<String> stringList(JSONArray array) throws Exception {
        if (array == null) return List.of();
        List<String> values = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            String value = array.getString(i).trim();
            if (!value.isEmpty()) values.add(value);
        }
        return values;
    }

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
