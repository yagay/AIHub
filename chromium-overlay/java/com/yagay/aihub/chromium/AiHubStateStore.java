package com.yagay.aihub.chromium;

import android.content.Context;
import android.content.SharedPreferences;

import com.yagay.aihub.core.AiAccount;
import com.yagay.aihub.core.AiSessionKey;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Small Android persistence layer for account metadata and the last selected session.
 * Browser cookies/site data stay inside WebEngine profiles and are never copied here.
 */
public final class AiHubStateStore {
    private static final String PREFS = "aihub_shell_state";
    private static final String KEY_ACCOUNTS = "accounts_json";
    private static final String KEY_PROVIDER = "current_provider";
    private static final String KEY_ACCOUNT = "current_account";
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

    public void saveCurrent(AiSessionKey key) {
        prefs.edit()
                .putString(KEY_PROVIDER, key.providerId())
                .putString(KEY_ACCOUNT, key.accountId())
                .apply();
    }

    public String savedProviderId() {
        return prefs.getString(KEY_PROVIDER, null);
    }

    public String savedAccountId() {
        return prefs.getString(KEY_ACCOUNT, null);
    }

    public String clientToken() {
        String existing = prefs.getString(KEY_CLIENT_TOKEN, null);
        if (existing != null && !existing.isBlank()) return existing;
        String created = UUID.randomUUID().toString().replace("-", "");
        prefs.edit().putString(KEY_CLIENT_TOKEN, created).apply();
        return created;
    }
}
