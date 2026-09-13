package com.yagay.aihub.chromium;

import android.content.Context;
import android.content.SharedPreferences;

import com.yagay.aihub.core.AiAccount;
import com.yagay.aihub.core.AiSessionKey;
import com.yagay.aihub.core.AiWorkspace;
import com.yagay.aihub.core.ProviderConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Persists shell metadata. Website login/cookies remain owned only by WebEngine profiles. */
public final class AiHubStateStore {
    private static final String PREFS = "aihub_shell_state";
    private static final String KEY_ACCOUNTS = "accounts_json";
    private static final String KEY_WORKSPACES = "workspaces_json";
    private static final String KEY_CUSTOM_PROVIDERS = "custom_provider_rules_json";
    private static final String KEY_PROVIDER = "current_provider";
    private static final String KEY_ACCOUNT = "current_account";
    private static final String KEY_WORKSPACE = "current_workspace";
    private static final String KEY_CLIENT_TOKEN = "client_token";

    private final SharedPreferences prefs;

    public AiHubStateStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<AiAccount> loadAccounts() {
        List<AiAccount> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_ACCOUNTS, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                out.add(new AiAccount(item.getString("id"), item.getString("providerId"),
                        item.getString("label"), item.getString("profileName")));
            }
        } catch (JSONException | IllegalArgumentException ignored) {}
        return out;
    }

    public void saveAccounts(List<AiAccount> accounts) {
        JSONArray array = new JSONArray();
        for (AiAccount account : accounts) {
            try {
                JSONObject item = new JSONObject();
                item.put("id", account.id());
                item.put("providerId", account.providerId());
                item.put("label", account.label());
                item.put("profileName", account.profileName());
                array.put(item);
            } catch (JSONException ignored) {}
        }
        prefs.edit().putString(KEY_ACCOUNTS, array.toString()).apply();
    }

    public List<AiWorkspace> loadWorkspaces() {
        List<AiWorkspace> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_WORKSPACES, "[]"));
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
                out.add(new AiWorkspace(item.getString("id"), item.getString("label"), providerAccounts));
            }
        } catch (JSONException | IllegalArgumentException ignored) {}
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
            } catch (JSONException ignored) {}
        }
        prefs.edit().putString(KEY_WORKSPACES, array.toString()).apply();
    }

    public List<String> loadCustomProviderRules() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(KEY_CUSTOM_PROVIDERS, "[]"));
            for (int i = 0; i < array.length(); i++) out.add(array.getString(i));
        } catch (JSONException ignored) {}
        return out;
    }

    public void saveCustomProvider(ProviderConfig provider) {
        List<String> rules = loadCustomProviderRules();
        List<String> next = new ArrayList<>();
        for (String rule : rules) {
            try {
                if (ProviderRuleCodec.decode(rule).provider().id().equals(provider.id())) continue;
            } catch (Exception ignored) {}
            next.add(rule);
        }
        next.add(ProviderRuleCodec.encode(provider, 10000 + next.size()));
        saveCustomProviderRules(next);
    }

    public boolean removeCustomProvider(String providerId) {
        List<String> next = new ArrayList<>();
        boolean removed = false;
        for (String rule : loadCustomProviderRules()) {
            try {
                if (ProviderRuleCodec.decode(rule).provider().id().equals(providerId)) {
                    removed = true;
                    continue;
                }
            } catch (Exception ignored) {}
            next.add(rule);
        }
        if (removed) saveCustomProviderRules(next);
        return removed;
    }

    private void saveCustomProviderRules(List<String> rules) {
        JSONArray array = new JSONArray();
        for (String rule : rules) array.put(rule);
        prefs.edit().putString(KEY_CUSTOM_PROVIDERS, array.toString()).apply();
    }

    public void saveCurrent(AiSessionKey key) {
        prefs.edit().putString(KEY_PROVIDER, key.providerId()).putString(KEY_ACCOUNT, key.accountId()).apply();
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
        return rotateClientToken();
    }

    public String rotateClientToken() {
        String created = UUID.randomUUID().toString().replace("-", "");
        prefs.edit().putString(KEY_CLIENT_TOKEN, created).apply();
        return created;
    }
}
