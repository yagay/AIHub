package com.yagay.aihub.core.runtime;

import com.yagay.aihub.core.AiAccount;
import com.yagay.aihub.core.AiSessionKey;
import com.yagay.aihub.core.ProviderConfig;
import java.util.List;

/** Browser-specific implementation. The business/core layer never references Chromium directly. */
public interface SessionRuntime {
    void open(AiSessionKey key, ProviderConfig provider, AiAccount account);
    void activate(AiSessionKey key);
    void close(AiSessionKey key);
    void sendText(AiSessionKey key, String text);
    void newChat(AiSessionKey key);
    void stop(AiSessionKey key);
    void attach(AiSessionKey key, List<String> uriStrings);
    void attachAndSend(AiSessionKey key, List<String> uriStrings, String text);
    void back(AiSessionKey key);
    void forward(AiSessionKey key);
    void reload(AiSessionKey key);
}
