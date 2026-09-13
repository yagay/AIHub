package com.yagay.aihub.browser;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.util.List;
import java.util.Locale;

public record BrowserProvider(
        String id,
        String modelId,
        String name,
        String homeUrl,
        List<String> hosts,
        List<String> inputSelectors,
        List<String> sendSelectors,
        List<String> stopSelectors,
        List<String> assistantSelectors) {

    public BrowserProvider {
        hosts = copy(hosts);
        inputSelectors = copy(inputSelectors);
        sendSelectors = copy(sendSelectors);
        stopSelectors = copy(stopSelectors);
        assistantSelectors = copy(assistantSelectors);
    }

    public boolean ownsUrl(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            String host = URI.create(url).getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            for (String item : hosts) {
                String suffix = item.toLowerCase(Locale.ROOT);
                if (host.equals(suffix) || host.endsWith("." + suffix)) return true;
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    public JSONObject scriptConfig() {
        JSONObject out = new JSONObject();
        try {
            out.put("input", array(inputSelectors));
            out.put("send", array(sendSelectors));
            out.put("stop", array(stopSelectors));
            out.put("assistant", array(assistantSelectors));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        return out;
    }

    private static JSONArray array(List<String> values) {
        JSONArray array = new JSONArray();
        for (String value : values) array.put(value);
        return array;
    }

    private static List<String> copy(List<String> value) {
        return List.copyOf(value == null ? List.of() : value);
    }
}
