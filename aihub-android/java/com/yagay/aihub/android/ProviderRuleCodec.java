package com.yagay.aihub.android;

import com.yagay.aihub.core.AiCapability;
import com.yagay.aihub.core.ProviderConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Single JSON codec for built-in, custom and remotely updated provider rules. */
public final class ProviderRuleCodec {
    private ProviderRuleCodec() {}

    public record Decoded(int order, ProviderConfig provider) {}

    public static Decoded decode(String raw) throws Exception {
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

        ProviderConfig provider = new ProviderConfig(
                required(root, "id"),
                required(root, "displayName"),
                required(root, "homeUrl"),
                capabilities,
                stringList(selectors.optJSONArray("input")),
                stringList(selectors.optJSONArray("send")),
                stringList(selectors.optJSONArray("newChat")),
                stringList(selectors.optJSONArray("stop")));
        return new Decoded(root.optInt("order", 1000), provider);
    }

    public static String encode(ProviderConfig provider, int order) {
        try {
            JSONObject root = new JSONObject();
            root.put("id", provider.id());
            root.put("displayName", provider.displayName());
            root.put("homeUrl", provider.homeUrl());
            root.put("order", order);
            JSONArray caps = new JSONArray();
            provider.capabilities().forEach(capability -> caps.put(capability.name()));
            root.put("capabilities", caps);
            JSONObject selectors = new JSONObject();
            selectors.put("input", new JSONArray(provider.inputSelectors()));
            selectors.put("send", new JSONArray(provider.sendSelectors()));
            selectors.put("newChat", new JSONArray(provider.newChatSelectors()));
            selectors.put("stop", new JSONArray(provider.stopSelectors()));
            root.put("selectors", selectors);
            return root.toString();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
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
}
