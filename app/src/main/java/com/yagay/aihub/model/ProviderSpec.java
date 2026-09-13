package com.yagay.aihub.model;

import java.util.List;

/** Immutable description of one AI website. */
public record ProviderSpec(
        String id,
        String name,
        String homeUrl,
        List<String> inputSelectors,
        List<String> sendSelectors,
        List<String> newChatSelectors,
        List<String> stopSelectors) {

    public ProviderSpec {
        inputSelectors = List.copyOf(inputSelectors == null ? List.of() : inputSelectors);
        sendSelectors = List.copyOf(sendSelectors == null ? List.of() : sendSelectors);
        newChatSelectors = List.copyOf(newChatSelectors == null ? List.of() : newChatSelectors);
        stopSelectors = List.copyOf(stopSelectors == null ? List.of() : stopSelectors);
    }
}
