package com.yagay.aihub.api;

/**
 * Stable Binder API. Every exported operation requires the local client token. JSON is used for
 * list/status payloads so adding fields does not force third-party apps to immediately recompile
 * parcelables.
 */
interface IAiHubService {
    String listProvidersJson(String clientToken);
    String listAccountsJson(String clientToken, String providerId);
    String getCurrentSessionJson(String clientToken);

    boolean switchSession(String clientToken, String providerId, String accountId, String workspaceId);
    boolean sendText(String clientToken, String providerId, String accountId, String workspaceId, String text);
    boolean newChat(String clientToken);
    boolean stop(String clientToken);
    boolean attach(String clientToken, in List<String> uriStrings);
    boolean back(String clientToken);
    boolean forward(String clientToken);
    boolean reload(String clientToken);
    boolean nextProvider(String clientToken);
    boolean previousProvider(String clientToken);
}
