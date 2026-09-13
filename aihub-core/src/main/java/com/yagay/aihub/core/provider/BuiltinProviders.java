package com.yagay.aihub.core.provider;

import com.yagay.aihub.core.AiCapability;
import com.yagay.aihub.core.ProviderConfig;
import java.util.List;
import java.util.Set;

public final class BuiltinProviders {
    private BuiltinProviders() {}

    private static final Set<AiCapability> COMMON = Set.of(
            AiCapability.TEXT, AiCapability.FILE_UPLOAD,
            AiCapability.NEW_CHAT, AiCapability.STOP);

    public static ProviderRegistry createDefaultRegistry() {
        ProviderRegistry r = new ProviderRegistry();
        r.register(provider("chatgpt", "ChatGPT", "https://chatgpt.com/"));
        r.register(provider("claude", "Claude", "https://claude.ai/"));
        r.register(provider("gemini", "Gemini", "https://gemini.google.com/"));
        r.register(provider("grok", "Grok", "https://grok.com/"));
        r.register(provider("deepseek", "DeepSeek", "https://chat.deepseek.com/"));
        return r;
    }

    private static ProviderConfig provider(String id, String name, String url) {
        return new ProviderConfig(
                id, name, url, COMMON,
                List.of(), List.of(), List.of(), List.of());
    }
}
