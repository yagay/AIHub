package com.yagay.aihub.api;

/**
 * Stable minimal Binder API. JSON is used for list/status payloads so adding fields does not force
 * third-party apps to immediately recompile parcelables.
 */
interface IAiHubService {
    String listProvidersJson();
    String listAccountsJson(String providerId);
    String getCurrentSessionJson();

    boolean switchSession(String providerId, String accountId, String workspaceId);
    boolean sendText(String providerId, String accountId, String workspaceId, String text);
    boolean newChat();
    boolean stop();
    boolean nextProvider();
    boolean previousProvider();
}
