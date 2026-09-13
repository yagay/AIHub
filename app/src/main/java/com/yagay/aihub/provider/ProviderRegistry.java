package com.yagay.aihub.provider;

import com.yagay.aihub.model.ProviderSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Single source of truth for available AI providers. */
public final class ProviderRegistry {
    private final Map<String, AiProviderAdapter> adapters = new LinkedHashMap<>();

    public ProviderRegistry() {
        register(new GenericWebProviderAdapter(new ProviderSpec(
                "chatgpt", "ChatGPT", "https://chatgpt.com/",
                List.of("#prompt-textarea", "textarea"),
                List.of("button[data-testid='send-button']", "button[aria-label*='Send']"),
                List.of("a[href='/']", "button[aria-label*='New chat']"),
                List.of("button[aria-label*='Stop']"))));
        register(new GenericWebProviderAdapter(new ProviderSpec(
                "claude", "Claude", "https://claude.ai/new",
                List.of("div[contenteditable='true']", "textarea"),
                List.of("button[aria-label*='Send']"),
                List.of("a[href='/new']", "button[aria-label*='New']"),
                List.of("button[aria-label*='Stop']"))));
        register(new GenericWebProviderAdapter(new ProviderSpec(
                "gemini", "Gemini", "https://gemini.google.com/app",
                List.of(".ql-editor[contenteditable='true']", "div[contenteditable='true']", "textarea"),
                List.of("button[aria-label*='Send']", "button[aria-label*='submit']"),
                List.of("a[href='/app']", "button[aria-label*='New chat']"),
                List.of("button[aria-label*='Stop']"))));
        register(new GenericWebProviderAdapter(new ProviderSpec(
                "grok", "Grok", "https://grok.com/",
                List.of("textarea", "div[contenteditable='true']"),
                List.of("button[aria-label*='Send']"),
                List.of("a[href='/']", "button[aria-label*='New']"),
                List.of("button[aria-label*='Stop']"))));
        register(new GenericWebProviderAdapter(new ProviderSpec(
                "deepseek", "DeepSeek", "https://chat.deepseek.com/",
                List.of("textarea", "div[contenteditable='true']"),
                List.of("button[aria-label*='Send']"),
                List.of("button[aria-label*='New']", "a[href='/']"),
                List.of("button[aria-label*='Stop']"))));
    }

    public void register(AiProviderAdapter adapter) {
        adapters.put(adapter.spec().id(), adapter);
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
}
