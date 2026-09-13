package com.yagay.aihub.data;

import android.content.Context;
import android.content.SharedPreferences;

/** Small persistence boundary for UI state. */
public final class AppPreferences {
    private static final String PREFS = "aihub_preferences_v2";
    private static final String KEY_PROVIDER = "provider";
    private static final String KEY_APP_MODE = "app_mode";

    private final SharedPreferences prefs;

    public AppPreferences(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public String providerId() {
        return prefs.getString(KEY_PROVIDER, null);
    }

    public void setProviderId(String providerId) {
        prefs.edit().putString(KEY_PROVIDER, providerId).apply();
    }

    public boolean appModeEnabled() {
        return prefs.getBoolean(KEY_APP_MODE, true);
    }

    public void setAppModeEnabled(boolean enabled) {
        prefs.edit().putBoolean(KEY_APP_MODE, enabled).apply();
    }
}
