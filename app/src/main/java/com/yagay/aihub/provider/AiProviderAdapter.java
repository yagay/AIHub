package com.yagay.aihub.provider;

import com.yagay.aihub.model.ProviderSpec;

import org.json.JSONObject;

/** Stable boundary between native app behavior and one AI website. */
public interface AiProviderAdapter {
    ProviderSpec spec();
    JSONObject probeCommand();
    JSONObject sendCommand(String text);
    JSONObject newChatCommand();
    JSONObject stopCommand();
    JSONObject attachmentCommand();
    JSONObject presentationCommand(boolean appMode);
}
