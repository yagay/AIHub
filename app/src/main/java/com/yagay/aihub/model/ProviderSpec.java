package com.yagay.aihub.model;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/** Immutable description of one AI website. */
public record ProviderSpec(
        String id,
        String name,
        String homeUrl,
        List<String> allowedHosts,
        List<String> inputSelectors,
        List<String> sendSelectors,
        List<String> newChatSelectors,
        List<String> stopSelectors,
        List<String> attachmentSelectors) {

    public ProviderSpec {
        allowedHosts = copy(allowedHosts);
        inputSelectors = copy(inputSelectors);
        sendSelectors = copy(sendSelectors);
        newChatSelectors = copy(newChatSelectors);
        stopSelectors = copy(stopSelectors);
        attachmentSelectors = copy(attachmentSelectors);
    }

    public boolean ownsUrl(String url) {
        if (url == null || url.isBlank()) return false;
        try {
            URI uri = URI.create(url);
            String host = uri.getHost();
            if (host == null) return false;
            host = host.toLowerCase(Locale.ROOT);
            for (String item : allowedHosts) {
                String suffix = item.toLowerCase(Locale.ROOT);
                if (host.equals(suffix) || host.endsWith("." + suffix)) return true;
            }
        } catch (Exception ignored) {
            return false;
        }
        return false;
    }

    private static List<String> copy(List<String> value) {
        return List.copyOf(value == null ? List.of() : value);
    }
}
