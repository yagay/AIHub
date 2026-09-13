package com.yagay.aihub.app;

import java.util.List;

/** Built-in AI websites. Keep provider-specific selectors isolated in this one file. */
public final class ProviderCatalog {
    private ProviderCatalog() {}

    public static List<AiProvider> all() {
        return List.of(
                new AiProvider(
                        "chatgpt", "ChatGPT", "https://chatgpt.com/",
                        List.of("#prompt-textarea", "textarea"),
                        List.of("button[data-testid='send-button']", "button[aria-label*='Send']"),
                        List.of("a[href='/']", "button[aria-label*='New chat']"),
                        List.of("button[aria-label*='Stop']")
                ),
                new AiProvider(
                        "claude", "Claude", "https://claude.ai/new",
                        List.of("div[contenteditable='true']", "textarea"),
                        List.of("button[aria-label*='Send']"),
                        List.of("a[href='/new']", "button[aria-label*='New']"),
                        List.of("button[aria-label*='Stop']")
                ),
                new AiProvider(
                        "gemini", "Gemini", "https://gemini.google.com/app",
                        List.of(".ql-editor[contenteditable='true']", "div[contenteditable='true']", "textarea"),
                        List.of("button[aria-label*='Send']", "button[aria-label*='submit']"),
                        List.of("a[href='/app']", "button[aria-label*='New chat']"),
                        List.of("button[aria-label*='Stop']")
                ),
                new AiProvider(
                        "grok", "Grok", "https://grok.com/",
                        List.of("textarea", "div[contenteditable='true']"),
                        List.of("button[aria-label*='Send']"),
                        List.of("a[href='/']", "button[aria-label*='New']"),
                        List.of("button[aria-label*='Stop']")
                ),
                new AiProvider(
                        "deepseek", "DeepSeek", "https://chat.deepseek.com/",
                        List.of("textarea", "div[contenteditable='true']"),
                        List.of("button[aria-label*='Send']"),
                        List.of("button[aria-label*='New']", "a[href='/']"),
                        List.of("button[aria-label*='Stop']")
                )
        );
    }

    public static AiProvider byId(String id) {
        for (AiProvider provider : all()) {
            if (provider.id().equals(id)) return provider;
        }
        return all().get(0);
    }
}
