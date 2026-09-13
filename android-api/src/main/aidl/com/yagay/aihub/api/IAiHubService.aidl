package com.yagay.aihub.api;

/**
 * Stable Binder API. Every exported operation requires the local client token. JSON is used for
 * list/status payloads so fields can evolve without parcelable churn.
 */
interface IAiHubService {
    String listProvidersJson(String clientToken);
    String getCurrentSessionJson(String clientToken);

    boolean switchProvider(String clientToken, String providerId);
    boolean sendText(String clientToken, String providerId, String text);
    boolean newChat(String clientToken);
    boolean stop(String clientToken);
    boolean attach(String clientToken, in List<String> uriStrings);
    boolean attachAndSend(String clientToken, in List<String> uriStrings, String text);
    boolean back(String clientToken);
    boolean forward(String clientToken);
    boolean reload(String clientToken);
    boolean nextProvider(String clientToken);
    boolean previousProvider(String clientToken);
}
