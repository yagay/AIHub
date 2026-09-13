package com.yagay.aihub.app;

import java.util.List;

/** One AI website definition. Website-specific DOM details live here, not in the UI. */
public record AiProvider(
        String id,
        String name,
        String homeUrl,
        List<String> inputSelectors,
        List<String> sendSelectors,
        List<String> newChatSelectors,
        List<String> stopSelectors) {

    public AiProvider {
        inputSelectors = List.copyOf(inputSelectors);
        sendSelectors = List.copyOf(sendSelectors);
        newChatSelectors = List.copyOf(newChatSelectors);
        stopSelectors = List.copyOf(stopSelectors);
    }
}
