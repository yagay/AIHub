package com.yagay.aihub.api;

/** Public integration contract for third-party apps and automation tools. */
public final class AiHubContract {
    private AiHubContract() {}

    public static final String ACTION_BIND_SERVICE = "com.yagay.aihub.action.BIND";
    public static final String ACTION_SWITCH = "com.yagay.aihub.action.SWITCH";
    public static final String ACTION_SEND_TEXT = "com.yagay.aihub.action.SEND_TEXT";
    public static final String ACTION_NEW_CHAT = "com.yagay.aihub.action.NEW_CHAT";
    public static final String ACTION_STOP = "com.yagay.aihub.action.STOP";
    public static final String ACTION_ATTACH = "com.yagay.aihub.action.ATTACH";
    public static final String ACTION_BACK = "com.yagay.aihub.action.BACK";
    public static final String ACTION_FORWARD = "com.yagay.aihub.action.FORWARD";
    public static final String ACTION_RELOAD = "com.yagay.aihub.action.RELOAD";
    public static final String ACTION_NEXT_PROVIDER = "com.yagay.aihub.action.NEXT_PROVIDER";
    public static final String ACTION_PREVIOUS_PROVIDER = "com.yagay.aihub.action.PREVIOUS_PROVIDER";

    public static final String EXTRA_PROVIDER_ID = "provider_id";
    public static final String EXTRA_TEXT = "text";
    public static final String EXTRA_URI_LIST = "uri_list";
    public static final String EXTRA_CLIENT_TOKEN = "client_token";

    public static final String DEEP_LINK_SCHEME = "aihub";
}
