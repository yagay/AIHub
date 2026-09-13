package com.yagay.aihub.provider;

import android.content.Context;

import com.yagay.aihub.model.ProviderAction;
import com.yagay.aihub.model.ProviderSpec;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Loads provider differences from one configuration file, then wraps them in shared adapters. */
public final class ProviderRegistry {
    private final Map<String, AiProviderAdapter> adapters = new LinkedHashMap<>();

    public ProviderRegistry(Context context) {
        for (ProviderSpec spec : loadSpecs(context)) {
            validate(spec);
            register(new GenericWebProviderAdapter(spec));
        }
        if (adapters.isEmpty()) throw new IllegalStateException("No AI providers configured");
    }

    public void register(AiProviderAdapter adapter) {
        String id = adapter.spec().id();
        if (adapters.containsKey(id)) throw new IllegalArgumentException("Duplicate provider: " + id);
        adapters.put(id, adapter);
    }

    public AiProviderAdapter require(String providerId) {
        AiProviderAdapter adapter = adapters.get(providerId);
        if (adapter == null) throw new IllegalArgumentException("Unknown provider: " + providerId);
        return adapter;
    }

    public List<AiProviderAdapter> all() {
        return List.copyOf(new ArrayList<>(adapters.values()));
    }

    public boolean contains(String providerId) {
        return adapters.containsKey(providerId);
    }

    private static List<ProviderSpec> loadSpecs(Context context) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                context.getAssets().open("providers.json"), StandardCharsets.UTF_8))) {
            StringBuilder json = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) json.append(line).append('\n');

            JSONArray array = new JSONArray(json.toString());
            List<ProviderSpec> out = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                JSONObject selectors = item.optJSONObject("selectors");
                JSONObject ui = item.optJSONObject("ui");
                out.add(new ProviderSpec(
                        item.getString("id").trim(),
                        item.getString("name").trim(),
                        item.getString("homeUrl").trim(),
                        strings(item, "hosts"),
                        strings(selectors, "input"),
                        strings(selectors, "send"),
                        strings(selectors, "newChat"),
                        strings(selectors, "stop"),
                        strings(selectors, "attach"),
                        strings(ui, "hide"),
                        actions(ui)));
            }
            return out;
        } catch (Exception error) {
            throw new IllegalStateException("Cannot load providers.json", error);
        }
    }

    private static void validate(ProviderSpec spec) {
        if (!spec.id().matches("[a-z0-9][a-z0-9_-]*")) {
            throw new IllegalStateException("Invalid provider id: " + spec.id());
        }
        if (spec.name().isBlank()) throw new IllegalStateException("Provider name is empty: " + spec.id());
        if (!spec.homeUrl().startsWith("https://")) {
            throw new IllegalStateException("Provider homeUrl must use HTTPS: " + spec.id());
        }
        if (spec.allowedHosts().isEmpty() || !spec.ownsUrl(spec.homeUrl())) {
            throw new IllegalStateException("Provider hosts do not own homeUrl: " + spec.id());
        }
        if (spec.inputSelectors().isEmpty()) {
            throw new IllegalStateException("Provider has no input selector: " + spec.id());
        }
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (ProviderAction action : spec.appActions()) {
            if (!action.id().matches("[a-z0-9][a-z0-9_-]*")) {
                throw new IllegalStateException("Invalid APP action id " + action.id() + " for " + spec.id());
            }
            if (!seen.add(action.id())) {
                throw new IllegalStateException("Duplicate APP action " + action.id() + " for " + spec.id());
            }
            if (action.label().isBlank() || (action.selectors().isEmpty() && action.keywords().isEmpty())) {
                throw new IllegalStateException("Incomplete APP action " + action.id() + " for " + spec.id());
            }
        }
    }

    private static List<ProviderAction> actions(JSONObject ui) {
        if (ui == null) return List.of();
        JSONArray array = ui.optJSONArray("actions");
        if (array == null) return List.of();
        List<ProviderAction> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) continue;
            out.add(new ProviderAction(
                    item.optString("id", "").trim(),
                    item.optString("label", "").trim(),
                    strings(item, "selectors"),
                    strings(item, "keywords")));
        }
        return List.copyOf(out);
    }

    private static List<String> strings(JSONObject object, String key) {
        if (object == null) return List.of();
        JSONArray array = object.optJSONArray(key);
        if (array == null) return List.of();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "").trim();
            if (!value.isEmpty()) out.add(value);
        }
        return List.copyOf(out);
    }
}
