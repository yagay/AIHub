package com.yagay.aihub.provider;

import com.yagay.aihub.model.ProviderSpec;

/** Stable boundary between native app behavior and one AI website. */
public interface AiProviderAdapter {
    ProviderSpec spec();
    String sendScript(String text);
    String newChatScript();
    String stopScript();
    String attachmentScript();
    String conversationScript();
}
