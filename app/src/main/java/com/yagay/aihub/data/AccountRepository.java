package com.yagay.aihub.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.yagay.aihub.model.AccountProfile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Persists account labels only. Website credentials stay inside the corresponding WebView profile. */
public final class AccountRepository {
    private static final String PREFS = "aihub_accounts_v2";
    private final SharedPreferences prefs;

    public AccountRepository(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<AccountProfile> list(String providerId) {
        List<AccountProfile> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString(keyAccounts(providerId), "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                out.add(new AccountProfile(item.getString("id"), item.getString("name")));
            }
        } catch (Exception ignored) {}
        if (out.isEmpty()) {
            out.add(new AccountProfile("default", "Default"));
            save(providerId, out);
        }
        return List.copyOf(out);
    }

    public AccountProfile selected(String providerId) {
        List<AccountProfile> all = list(providerId);
        String selectedId = prefs.getString(keySelected(providerId), null);
        if (selectedId != null) {
            for (AccountProfile account : all) {
                if (account.id().equals(selectedId)) return account;
            }
        }
        AccountProfile first = all.get(0);
        select(providerId, first.id());
        return first;
    }

    public AccountProfile add(String providerId, String requestedName) {
        List<AccountProfile> all = new ArrayList<>(list(providerId));
        String name = requestedName == null ? "" : requestedName.trim();
        if (name.isEmpty()) name = "Account " + (all.size() + 1);
        AccountProfile created = new AccountProfile(
                UUID.randomUUID().toString().replace("-", ""), name);
        all.add(created);
        save(providerId, all);
        select(providerId, created.id());
        return created;
    }

    public void select(String providerId, String accountId) {
        prefs.edit().putString(keySelected(providerId), accountId).apply();
    }

    /** Stable and globally unique name used by AndroidX WebKit multi-profile. */
    public String webProfileName(String providerId, AccountProfile account) {
        return "aihub_" + sanitize(providerId) + "_" + sanitize(account.id());
    }

    private void save(String providerId, List<AccountProfile> accounts) {
        JSONArray array = new JSONArray();
        for (AccountProfile account : accounts) {
            try {
                JSONObject item = new JSONObject();
                item.put("id", account.id());
                item.put("name", account.name());
                array.put(item);
            } catch (Exception ignored) {}
        }
        prefs.edit().putString(keyAccounts(providerId), array.toString()).apply();
    }

    private static String keyAccounts(String providerId) { return "accounts_" + providerId; }
    private static String keySelected(String providerId) { return "selected_" + providerId; }
    private static String sanitize(String value) { return value.replaceAll("[^A-Za-z0-9_.-]", "_"); }
}
