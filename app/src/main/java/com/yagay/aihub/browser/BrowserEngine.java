package com.yagay.aihub.browser;

public interface BrowserEngine {
    interface ChatListener {
        void onUpdate(String fullText, String chunk);
        void onFinish(String fullText);
        void onError(String code, String message);
    }

    void chat(String requestId, String modelId, String prompt, ChatListener listener);
    void cancel(String requestId);
    void openProvider(String modelId);
    void shutdown();
}
