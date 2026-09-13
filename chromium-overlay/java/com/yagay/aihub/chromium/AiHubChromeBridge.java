package com.yagay.aihub.chromium;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.ValueCallback;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.yagay.aihub.android.AiHubBrowserHost;

import org.chromium.chrome.R;
import org.chromium.chrome.browser.ChromeTabbedActivity;
import org.chromium.chrome.browser.tab.Tab;
import org.chromium.chrome.browser.tab.TabLaunchType;
import org.chromium.chrome.browser.tab.TabSelectionType;
import org.chromium.chrome.browser.tabmodel.TabClosureParams;
import org.chromium.chrome.browser.tabmodel.TabCreator;
import org.chromium.chrome.browser.tabmodel.TabModelSelector;
import org.chromium.chrome.browser.tabmodel.TabModelUtils;
import org.chromium.content_public.browser.JavaScriptCallback;
import org.chromium.content_public.browser.LoadUrlParams;
import org.chromium.content_public.browser.RenderFrameHost;
import org.chromium.content_public.browser.WebContents;
import org.chromium.content_public.common.IsolatedWorldIds;

/**
 * The single Java seam between AIHub and Chromium Chrome Android internals.
 *
 * Keep all Chrome-specific API churn here. AIHub core, provider rules and UI must never import
 * org.chromium.chrome.* or org.chromium.content_public.* directly.
 */
public final class AiHubChromeBridge implements AiHubBrowserHost {
    private static final String PREFS = "aihub_provider_tabs";
    private static final String TAB_PREFIX = "tab.";

    private final ChromeTabbedActivity activity;
    private final TabModelSelector selector;
    private final SharedPreferences prefs;

    public AiHubChromeBridge(ChromeTabbedActivity activity) {
        this.activity = activity;
        this.selector = activity.getTabModelSelector();
        this.prefs = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    @Override
    public Activity activity() {
        return activity;
    }

    @Override
    public ViewGroup overlayRoot() {
        View root = activity.findViewById(android.R.id.content);
        if (!(root instanceof ViewGroup)) {
            throw new IllegalStateException("Chrome android.R.id.content is not a ViewGroup");
        }
        return (ViewGroup) root;
    }

    @Override
    public @Nullable View chromeControlContainer() {
        return activity.findViewById(R.id.control_container);
    }

    @Override
    public void openOrSelectProvider(String providerId, String homeUrl) {
        TabModelUtils.runOnTabStateInitialized(selector, ignored -> {
            Tab retained = retainedTab(providerId);
            if (retained != null) {
                select(retained);
                return;
            }

            TabCreator creator = activity.getTabCreator(false);
            Tab created = creator.createNewTab(
                    new LoadUrlParams(homeUrl),
                    TabLaunchType.FROM_CHROME_UI,
                    selector.getCurrentTab());
            if (created == null) {
                onAiHubError("Chrome did not create a tab for " + providerId);
                return;
            }
            prefs.edit().putInt(key(providerId), created.getId()).apply();
            select(created);
        });
    }

    @Override
    public void selectProvider(String providerId) {
        TabModelUtils.runOnTabStateInitialized(selector, ignored -> {
            Tab tab = retainedTab(providerId);
            if (tab == null) {
                onAiHubError("Provider tab is no longer available: " + providerId);
                return;
            }
            select(tab);
        });
    }

    @Override
    public void closeProvider(String providerId) {
        int id = prefs.getInt(key(providerId), Tab.INVALID_TAB_ID);
        prefs.edit().remove(key(providerId)).apply();
        if (id == Tab.INVALID_TAB_ID) return;
        Tab tab = selector.getTabById(id);
        if (tab == null) return;
        selector.tryCloseTab(
                TabClosureParams.closeTab(tab).allowUndo(false).build(),
                /* allowDialog= */ false);
    }

    @Override
    public void evaluateJavaScript(String script, @Nullable ValueCallback<String> callback) {
        Tab tab = activeTab();
        if (tab == null) {
            onAiHubError("No active Chrome tab");
            return;
        }
        WebContents webContents = tab.getWebContents();
        if (webContents == null) {
            onAiHubError("Active Chrome tab has no WebContents");
            return;
        }
        RenderFrameHost frame = webContents.getMainFrame();
        if (frame == null || !frame.isRenderFrameLive()) {
            onAiHubError("Active page has no live main frame");
            return;
        }

        JavaScriptCallback chromeCallback = callback == null
                ? null
                : callback::onReceiveValue;
        frame.executeJavaScriptInIsolatedWorld(
                script,
                IsolatedWorldIds.ISOLATED_WORLD_ID_MAX,
                chromeCallback);
    }

    @Override
    public void back() {
        Tab tab = activeTab();
        if (tab != null && tab.canGoBack()) tab.goBack();
    }

    @Override
    public void forward() {
        Tab tab = activeTab();
        if (tab != null && tab.canGoForward()) tab.goForward();
    }

    @Override
    public void reload() {
        Tab tab = activeTab();
        if (tab != null) tab.reload();
    }

    @Override
    public void stopLoading() {
        Tab tab = activeTab();
        if (tab != null) tab.stopLoading();
    }

    @Override
    public String currentUrl() {
        Tab tab = activeTab();
        return tab == null ? "" : tab.getUrl().getSpec();
    }

    @Override
    public boolean isLoading() {
        Tab tab = activeTab();
        return tab != null && tab.isLoading();
    }

    @Override
    public int loadProgress() {
        Tab tab = activeTab();
        if (tab == null) return 0;
        return Math.max(0, Math.min(100, Math.round(tab.getProgress())));
    }

    @Override
    public void onAiHubError(String message) {
        Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
    }

    private @Nullable Tab activeTab() {
        return selector.getCurrentTab();
    }

    private @Nullable Tab retainedTab(String providerId) {
        int id = prefs.getInt(key(providerId), Tab.INVALID_TAB_ID);
        if (id == Tab.INVALID_TAB_ID) return null;
        Tab tab = selector.getTabById(id);
        if (tab == null || tab.isOffTheRecord()) {
            prefs.edit().remove(key(providerId)).apply();
            return null;
        }
        return tab;
    }

    private void select(Tab tab) {
        selector.selectModel(false);
        TabModelUtils.selectTabById(selector, tab.getId(), TabSelectionType.FROM_USER);
    }

    private static String key(String providerId) {
        return TAB_PREFIX + providerId;
    }
}
