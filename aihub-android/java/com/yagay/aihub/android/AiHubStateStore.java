package com.yagay.aihub.android;

import android.content.Context;
import android.content.SharedPreferences;

import com.yagay.aihub.core.AiSessionKey;
import com.yagay.aihub.core.ProviderConfig;

import org.json.JSONArray;
import org.json.JSONException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Persists only AIHub metadata; website login/session data remains owned by the browser engine. */
public final class AiHubStateStore {
    private static final String PREFS = "aihub_shell_state";
    private static final String KEY_CUSTOM_PROVIDERS = "custom_provider_rules_json";
    private static final String KEY_REMOTE_RULE_BUNDLE = "remote_rule_bundle";
    private static final String KEY_PREVIOUS_REMOTE_RULE_BUNDLE = "previous_remote_rule_bundle";
    private static final String KEY_PROVIDER = "current_provider";
    private static final String KEY_CLIENT_TOKEN = "client_token";
    private static final String KEY_APP_MODE = "app_mode_enabled";

    private final Context context;
    private final SharedPreferences prefs;

    public AiHubStateStore(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    Context context() {
        return context;
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
        next.add(ProviderRuleCodec.encode(provider, 10_000 + next.size()));
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

    public String remoteRuleBundle() {
        return prefs.getString(KEY_REMOTE_RULE_BUNDLE, null);
    }

    public void installRemoteRuleBundle(String verifiedEnvelope) {
        String current = remoteRuleBundle();
        SharedPreferences.Editor editor = prefs.edit();
        if (current == null || current.isBlank()) editor.remove(KEY_PREVIOUS_REMOTE_RULE_BUNDLE);
        else editor.putString(KEY_PREVIOUS_REMOTE_RULE_BUNDLE, current);
        editor.putString(KEY_REMOTE_RULE_BUNDLE, verifiedEnvelope).apply();
    }

    public boolean canRollbackRemoteRuleBundle() {
        String previous = prefs.getString(KEY_PREVIOUS_REMOTE_RULE_BUNDLE, null);
        return previous != null && !previous.isBlank();
    }

    public boolean rollbackRemoteRuleBundle() {
        String previous = prefs.getString(KEY_PREVIOUS_REMOTE_RULE_BUNDLE, null);
        if (previous == null || previous.isBlank()) return false;
        String current = remoteRuleBundle();
        SharedPreferences.Editor editor = prefs.edit().putString(KEY_REMOTE_RULE_BUNDLE, previous);
        if (current == null || current.isBlank()) editor.remove(KEY_PREVIOUS_REMOTE_RULE_BUNDLE);
        else editor.putString(KEY_PREVIOUS_REMOTE_RULE_BUNDLE, current);
        editor.apply();
        return true;
    }

    public void clearRemoteRuleBundle() {
        prefs.edit().remove(KEY_REMOTE_RULE_BUNDLE).remove(KEY_PREVIOUS_REMOTE_RULE_BUNDLE).apply();
    }

    public void saveCurrent(AiSessionKey key) {
        prefs.edit().putString(KEY_PROVIDER, key.providerId()).apply();
    }

    public String savedProviderId() {
        return prefs.getString(KEY_PROVIDER, null);
    }

    /** App mode replaces common website chrome/composer controls with AIHub's native controls. */
    public boolean appModeEnabled() {
        return prefs.getBoolean(KEY_APP_MODE, true);
    }

    public void saveAppModeEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_APP_MODE, enabled).apply();
    }

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
