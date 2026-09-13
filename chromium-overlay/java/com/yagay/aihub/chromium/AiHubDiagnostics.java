package com.yagay.aihub.chromium;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.yagay.aihub.core.AiSessionKey;
import com.yagay.aihub.core.ProviderConfig;

import org.json.JSONArray;
import org.json.JSONObject;

/** Builds a support snapshot without cookies, login tokens or page storage. */
public final class AiHubDiagnostics {
    private AiHubDiagnostics() {}

    public static ListenableFuture<String> snapshot(
            AiHubBootstrap.Graph graph,
            WebEngineSessionRuntime runtime) {
        AiSessionKey key = graph.sessions.currentKey();
        return Futures.transformAsync(
                runtime.currentUrl(key),
                url -> Futures.transform(
                        runtime.probe(key),
                        probe -> buildJson(graph, key, url, probe),
                        Runnable::run),
                Runnable::run);
    }

    private static String buildJson(
            AiHubBootstrap.Graph graph,
            AiSessionKey key,
            String currentUrl,
            String probeJson) {
        JSONObject root = new JSONObject();
        try {
            root.put("format", "aihub-diagnostics-v2");
            root.put("providerId", key.providerId());
            root.put("currentUrl", currentUrl == null ? "" : currentUrl);
            try {
                root.put("pageProbe", probeJson == null ? JSONObject.NULL : new JSONObject(probeJson));
            } catch (Exception ignored) {
                root.put("pageProbeRaw", probeJson == null ? "" : probeJson);
            }

            JSONArray providers = new JSONArray();
            for (ProviderConfig provider : graph.providers.all()) {
                JSONObject item = new JSONObject();
                item.put("id", provider.id());
                item.put("displayName", provider.displayName());
                item.put("homeUrl", provider.homeUrl());
                JSONArray capabilities = new JSONArray();
                provider.capabilities().forEach(capability -> capabilities.put(capability.name()));
                item.put("capabilities", capabilities);
                item.put("inputSelectorCount", provider.inputSelectors().size());
                item.put("sendSelectorCount", provider.sendSelectors().size());
                item.put("newChatSelectorCount", provider.newChatSelectors().size());
                item.put("stopSelectorCount", provider.stopSelectors().size());
                providers.put(item);
            }
            root.put("providers", providers);

            JSONArray warnings = new JSONArray();
            graph.providerWarnings.forEach(warnings::put);
            root.put("providerWarnings", warnings);
        } catch (Exception error) {
            return "{\"format\":\"aihub-diagnostics-v2\",\"error\":\""
                    + escape(error.toString()) + "\"}";
        }
        return root.toString(2);
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
