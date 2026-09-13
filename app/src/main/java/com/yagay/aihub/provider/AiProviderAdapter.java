package com.yagay.aihub.provider;

import com.yagay.aihub.model.ProviderSpec;

/**
 * Stable boundary between the app and an AI website.
 * UI/session code never contains provider-specific DOM knowledge.
 */
public interface AiProviderAdapter {
    ProviderSpec spec();
    String sendScript(String text);
    String newChatScript();
    String stopScript();
    String appModeScript(boolean enabled);
}
