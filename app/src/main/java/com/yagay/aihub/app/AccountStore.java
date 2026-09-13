package com.yagay.aihub.app;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Stores only account labels/profile ids. Website credentials remain inside the WebView profile. */
public final class AccountStore {
    public record Account(String id, String name) {}

    private static final String PREFS = "aihub_accounts_v1";
    private final SharedPreferences prefs;

    public AccountStore(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public List<Account> accounts(String providerId) {
        List<Account> out = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(prefs.getString("accounts_" + providerId, "[]"));
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                out.add(new Account(item.getString("id"), item.getString("name")));
            }
        } catch (Exception ignored) {}
        if (out.isEmpty()) {
            out.add(new Account("default", "Default"));
            save(providerId, out);
        }
        return List.copyOf(out);
    }

    public Account add(String providerId, String displayName) {
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty()) name = "Account " + (accounts(providerId).size() + 1);
        Account account = new Account(UUID.randomUUID().toString().replace("-", ""), name);
        List<Account> next = new ArrayList<>(accounts(providerId));
        next.add(account);
        save(providerId, next);
        select(providerId, account.id());
        return account;
    }

    public Account selected(String providerId) {
        List<Account> all = accounts(providerId);
        String saved = prefs.getString("selected_" + providerId, null);
        if (saved != null) {
            for (Account account : all) if (account.id().equals(saved)) return account;
        }
        Account first = all.get(0);
        select(providerId, first.id());
        return first;
    }

    public void select(String providerId, String accountId) {
        prefs.edit().putString("selected_" + providerId, accountId).apply();
    }

    public String profileName(String providerId, Account account) {
        return "aihub_" + providerId + "_" + account.id();
    }

    private void save(String providerId, List<Account> accounts) {
        JSONArray array = new JSONArray();
        for (Account account : accounts) {
            JSONObject item = new JSONObject();
            try {
                item.put("id", account.id());
                item.put("name", account.name());
                array.put(item);
            } catch (Exception ignored) {}
        }
        prefs.edit().putString("accounts_" + providerId, array.toString()).apply();
    }
}
