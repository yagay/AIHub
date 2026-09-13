package com.yagay.aihub.android;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;

import androidx.annotation.Nullable;

/**
 * Stable Android-side browser boundary.
 *
 * AIHub UI/runtime depend only on this interface. The Chromium fork adapter is the only code that
 * imports Chrome internal classes such as ChromeTabbedActivity, Tab and TabModelSelector.
 */
public interface AiHubBrowserHost {
    Activity activity();
    ViewGroup overlayRoot();
    @Nullable View chromeControlContainer();

    void openOrSelectProvider(String providerId, String homeUrl);
    void selectProvider(String providerId);
    void closeProvider(String providerId);

    void evaluateJavaScript(String script, @Nullable ValueCallback<String> callback);

    void back();
    void forward();
    void reload();
    void stopLoading();

    String currentUrl();
    boolean isLoading();
    int loadProgress();
}
