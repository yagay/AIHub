package com.yagay.aihub.android;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;

import androidx.annotation.Nullable;

/**
 * Stable browser boundary used by AIHub UI and session logic.
 *
 * Engine-specific details belong in the app-side host implementation. The current main build uses
 * Android System WebView, while the stable AIHub layers know nothing about WebView internals.
 */
public interface AiHubBrowserHost {
    Activity activity();
    ViewGroup overlayRoot();

    /** Optional native browser controls that AIHub can hide/show; null for the WebView app. */
    @Nullable View browserControlContainer();

    /** Opens a retained provider page, or selects it if it already exists. */
    void openOrSelectProvider(String providerId, String homeUrl);
    void selectProvider(String providerId);
    void closeProvider(String providerId);

    /** Executes generic AIHub DOM logic in the currently active provider page. */
    void evaluateJavaScript(String script, @Nullable ValueCallback<String> callback);

    void back();
    void forward();
    void reload();
    void stopLoading();

    String currentUrl();
    boolean isLoading();
    int loadProgress();

    /** Stable UI/runtime error surface; the concrete browser host decides how to present it. */
    void onAiHubError(String message);
}
