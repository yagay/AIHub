package com.yagay.aihub.model;

import java.util.List;

/** One provider-specific website control exposed through the shared native APP toolbar. */
public record ProviderAction(
        String id,
        String label,
        String pick,
        List<String> selectors,
        List<String> keywords) {

    public ProviderAction {
        id = id == null ? "" : id.trim();
        label = label == null ? id : label.trim();
        pick = pick == null || pick.isBlank() ? "first" : pick.trim();
        selectors = List.copyOf(selectors == null ? List.of() : selectors);
        keywords = List.copyOf(keywords == null ? List.of() : keywords);
    }
}
