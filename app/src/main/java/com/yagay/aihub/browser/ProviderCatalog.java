package com.yagay.aihub.browser;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ProviderCatalog {
    private final Map<String, BrowserProvider> byModel = new LinkedHashMap<>();

    public ProviderCatalog(Context context) {
        for (BrowserProvider provider : load(context)) {
            if (byModel.put(provider.modelId(), provider) != null) {
                throw new IllegalStateException("Duplicate browser model: " + provider.modelId());
            }
        }
        if (byModel.isEmpty()) throw new IllegalStateException("No browser providers configured");
    }

    public BrowserProvider requireModel(String modelId) {
        BrowserProvider provider = byModel.get(modelId);
        if (provider == null) provider = byModel.get("chatgpt-web");
        if (provider == null) throw new IllegalArgumentException("Unknown model: " + modelId);
        return provider;
    }

    public List<BrowserProvider> all() {
        return List.copyOf(byModel.values());
    }

    private static List<BrowserProvider> load(Context context) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("providers.json"), StandardCharsets.UTF_8))) {
            StringBuilder json = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) json.append(line).append('\n');
            JSONArray array = new JSONArray(json.toString());
            List<BrowserProvider> result = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                JSONObject selectors = item.getJSONObject("selectors");
                result.add(new BrowserProvider(
                        item.getString("id"),
                        item.getString("model"),
                        item.getString("name"),
                        item.getString("homeUrl"),
                        strings(item.getJSONArray("hosts")),
                        strings(selectors.getJSONArray("input")),
                        strings(selectors.getJSONArray("send")),
                        strings(selectors.getJSONArray("stop")),
                        strings(selectors.getJSONArray("assistant"))));
            }
            return result;
        } catch (Exception error) {
            throw new IllegalStateException("Cannot load providers.json", error);
        }
    }

    private static List<String> strings(JSONArray array) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "").trim();
            if (!value.isEmpty()) result.add(value);
        }
        return List.copyOf(result);
    }
}
