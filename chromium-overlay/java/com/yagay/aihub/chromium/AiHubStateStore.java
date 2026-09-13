package com.yagay.aihub.chromium;

import android.content.Context;
import android.content.SharedPreferences;

import com.yagay.aihub.core.AiAccount;
import com.yagay.aihub.core.AiSessionKey;
import com.yagay.aihub.core.AiWorkspace;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Android persistence layer for account/workspace metadata and the last selected session.
 * Browser cookies/site data stay inside WebEngine profiles and are never copied here.
 */
public final class AiHubStateStore {
    private static final String PREFS = "aihub_shell_state";
    private static final String KEY_ACCOUNTS = "accounts_json";
    private static final String KEY_WORKSPACES = "workspaces_json";
    private static final String KEY_PROVIDER = "current_provider";
    private static final String KEY_ACCOUNT = "current_account";
    private static final String KEY_WORKSPACE = "current_workspace";
    private static final String KEY_CLIENT_TOKEN = "client_token";

    private final SharedPreferences prefs;

    public AiHubStateStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<AiAccount> loadAccounts() {
        String raw = prefs.getString(KEY_ACCOUNTS, "[]");
        List<AiAccount> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                out.add(new AiAccount(
                        item.getString("id"),
                        item.getString("providerId"),
                        item.getString("label"),
                        item.getString("profileName")));
            }
        } catch (JSONException | IllegalArgumentException ignored) {
            // Corrupt UI metadata must never affect the underlying browser profile data.
        }
        return out;
    }

    public void saveAccounts(List<AiAccount> accounts) {
        JSONArray array = new JSONArray();
        for (AiAccount account : accounts) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", account.id());
                item.put("providerId", account.providerId());
                item.put("label", account.label());
                item.put("profileName", account.profileName());
                array.put(item);
            } catch (JSONException ignored) {
            }
        }
        prefs.edit().putString(KEY_ACCOUNTS, array.toString()).apply();
    }

    public List<AiWorkspace> loadWorkspaces() {
        String raw = prefs.getString(KEY_WORKSPACES, "[]");
        List<AiWorkspace> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(raw);
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                JSONObject mapping = item.optJSONObject("providerAccounts");
                Map<String, String> providerAccounts = new LinkedHashMap<>();
                if (mapping != null) {
                    var keys = mapping.keys();
                    while (keys.hasNext()) {
                        String providerId = keys.next();
                        providerAccounts.put(providerId, mapping.getString(providerId));
                    }
                }
                out.add(new AiWorkspace(
                        item.getString("id"),
                        item.getString("label"),
                        providerAccounts));
            }
        } catch (JSONException | IllegalArgumentException ignored) {
        }
        return out;
    }

    public void saveWorkspaces(List<AiWorkspace> workspaces) {
        JSONArray array = new JSONArray();
        for (AiWorkspace workspace : workspaces) {
            try {
                JSONObject item = new JSONObject();
                item.put("id", workspace.id());
                item.put("label", workspace.label());
                JSONObject mapping = new JSONObject();
                for (Map.Entry<String, String> entry : workspace.providerAccounts().entrySet()) {
                    mapping.put(entry.getKey(), entry.getValue());
                }
                item.put("providerAccounts", mapping);
                array.put(item);
            } catch (JSONException ignored) {
            }
        }
        prefs.edit().putString(KEY_WORKSPACES, array.toString()).apply();
    }

    public void saveCurrent(AiSessionKey key) {
        prefs.edit()
                .putString(KEY_PROVIDER, key.providerId())
                .putString(KEY_ACCOUNT, key.accountId())
                .apply();
    }

    public void saveWorkspaceId(String workspaceId) {
        if (workspaceId == null) prefs.edit().remove(KEY_WORKSPACE).apply();
        else prefs.edit().putString(KEY_WORKSPACE, workspaceId).apply();
    }

    public String savedProviderId() { return prefs.getString(KEY_PROVIDER, null); }
    public String savedAccountId() { return prefs.getString(KEY_ACCOUNT, null); }
    public String savedWorkspaceId() { return prefs.getString(KEY_WORKSPACE, null); }

    public String clientToken() {
        String existing = prefs.getString(KEY_CLIENT_TOKEN, null);
        if (existing != null && !existing.isBlank()) return existing;
        String created = UUID.randomUUID().toString().replace("-", "");
        prefs.edit().putString(KEY_CLIENT_TOKEN, created).apply();
        return created;
    }

    public String rotateClientToken() {
        String created = UUID.randomUUID().toString().replace("-", "");
        prefs.edit().putString(KEY_CLIENT_TOKEN, created).apply();
        return created;
    }
}
