package com.yagay.aihub.chromium;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

import com.yagay.aihub.api.AiHubContract;
import com.yagay.aihub.api.IAiHubService;
import com.yagay.aihub.core.AiAccount;
import com.yagay.aihub.core.ProviderConfig;
import com.yagay.aihub.core.provider.ProviderRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Token-gated Binder facade. Browser mutations are routed back through the shell command bus. */
public final class AiHubBinderService extends Service {
    private AiHubStateStore stateStore;

    @Override
    public void onCreate() {
        super.onCreate();
        stateStore = new AiHubStateStore(this);
    }

    private final IAiHubService.Stub binder = new IAiHubService.Stub() {
        @Override
        public String listProvidersJson(String clientToken) {
            if (!authorized(clientToken)) return "[]";
            ProviderRegistry registry = new ProviderRuleLoader(AiHubBinderService.this).load();
            JSONArray array = new JSONArray();
            for (ProviderConfig provider : registry.all()) {
                JSONObject item = new JSONObject();
                try {
                    item.put("id", provider.id());
                    item.put("displayName", provider.displayName());
                    item.put("homeUrl", provider.homeUrl());
                    JSONArray caps = new JSONArray();
                    provider.capabilities().forEach(capability -> caps.put(capability.name()));
                    item.put("capabilities", caps);
                    array.put(item);
                } catch (Exception ignored) {}
            }
            return array.toString();
        }

        @Override
        public String listAccountsJson(String clientToken, String providerId) {
            if (!authorized(clientToken)) return "[]";
            JSONArray array = new JSONArray();
            for (AiAccount account : stateStore.loadAccounts()) {
                if (providerId != null && !providerId.isBlank() && !providerId.equals(account.providerId())) continue;
                JSONObject item = new JSONObject();
                try {
                    item.put("id", account.id());
                    item.put("providerId", account.providerId());
                    item.put("label", account.label());
                    array.put(item);
                } catch (Exception ignored) {}
            }
            return array.toString();
        }

        @Override
        public String getCurrentSessionJson(String clientToken) {
            if (!authorized(clientToken)) return "{}";
            JSONObject object = new JSONObject();
            try {
                object.put("providerId", stateStore.savedProviderId());
                object.put("accountId", stateStore.savedAccountId());
                object.put("workspaceId", stateStore.savedWorkspaceId());
            } catch (Exception ignored) {}
            return object.toString();
        }

        @Override public boolean switchSession(String token, String provider, String account, String workspace) {
            return start(token, AiHubContract.ACTION_SWITCH, provider, account, workspace, null, null);
        }
        @Override public boolean sendText(String token, String provider, String account, String workspace, String text) {
            return start(token, AiHubContract.ACTION_SEND_TEXT, provider, account, workspace, text, null);
        }
        @Override public boolean newChat(String token) { return startSimple(token, AiHubContract.ACTION_NEW_CHAT); }
        @Override public boolean stop(String token) { return startSimple(token, AiHubContract.ACTION_STOP); }
        @Override public boolean back(String token) { return startSimple(token, AiHubContract.ACTION_BACK); }
        @Override public boolean forward(String token) { return startSimple(token, AiHubContract.ACTION_FORWARD); }
        @Override public boolean reload(String token) { return startSimple(token, AiHubContract.ACTION_RELOAD); }
        @Override public boolean nextProvider(String token) { return startSimple(token, AiHubContract.ACTION_NEXT_PROVIDER); }
        @Override public boolean previousProvider(String token) { return startSimple(token, AiHubContract.ACTION_PREVIOUS_PROVIDER); }

        @Override
        public boolean attach(String token, List<String> uriStrings) {
            return start(token, AiHubContract.ACTION_ATTACH, null, null, null, null,
                    uriStrings == null ? List.of() : uriStrings);
        }
    };

    @Override
    public IBinder onBind(Intent intent) { return binder; }

    private boolean startSimple(String token, String action) {
        return start(token, action, null, null, null, null, null);
    }

    private boolean start(
            String token,
            String action,
            String provider,
            String account,
            String workspace,
            String text,
            List<String> uris) {
        if (!authorized(token)) return false;
        Intent intent = new Intent(this, AiHubShellActivity.class)
                .setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(AiHubContract.EXTRA_CLIENT_TOKEN, token);
        if (provider != null) intent.putExtra(AiHubContract.EXTRA_PROVIDER_ID, provider);
        if (account != null) intent.putExtra(AiHubContract.EXTRA_ACCOUNT_ID, account);
        if (workspace != null) intent.putExtra(AiHubContract.EXTRA_WORKSPACE_ID, workspace);
        if (text != null) intent.putExtra(AiHubContract.EXTRA_TEXT, text);
        if (uris != null) intent.putStringArrayListExtra(AiHubContract.EXTRA_URI_LIST, new ArrayList<>(uris));
        try {
            startActivity(intent);
            return true;
        } catch (RuntimeException error) {
            return false;
        }
    }

    private boolean authorized(String supplied) {
        if (supplied == null || supplied.isBlank()) return false;
        byte[] a = supplied.getBytes(StandardCharsets.UTF_8);
        byte[] b = stateStore.clientToken().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }
}
