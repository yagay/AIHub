package com.yagay.aihub.chromium;

import android.content.Context;
import android.net.Uri;
import android.os.Looper;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.chromium.webengine.FragmentParams;
import org.chromium.webengine.Tab;
import org.chromium.webengine.TabManager;
import org.chromium.webengine.WebFragment;
import org.chromium.webengine.WebSandbox;

/**
 * Keeps Chromium/WebEngine API churn in one class.
 *
 * This implementation follows the public WebEngine shell pattern: WebSandbox.create(),
 * createFragment(FragmentParams), fragment.getTabManager(), create/get active Tab.
 */
public final class AiWebEngineHost implements WebEngineSessionRuntime.WebEngineHost {
    private final Context context;
    private final FragmentManager fragmentManager;
    private final int containerViewId;
    private final ListenableFuture<WebSandbox> sandboxFuture;
    private final Map<String, WebFragment> fragments = new HashMap<>();
    private final Map<String, ListenableFuture<Tab>> tabs = new HashMap<>();
    private String visibleProfile;

    public AiWebEngineHost(Context context, FragmentManager fragmentManager, int containerViewId) {
        this.context = context.getApplicationContext();
        this.fragmentManager = fragmentManager;
        this.containerViewId = containerViewId;
        this.sandboxFuture = WebSandbox.create(this.context);
    }

    @Override
    public ListenableFuture<Tab> openOrRestoreTab(
            String profileName, String persistenceId, String url) {
        requireMainThread();
        ListenableFuture<Tab> existing = tabs.get(profileName);
        if (existing != null) return existing;

        ListenableFuture<Tab> created = Futures.transformAsync(
                sandboxFuture,
                sandbox -> {
                    WebFragment fragment = getOrCreateFragment(sandbox, profileName, persistenceId);
                    return Futures.transformAsync(
                            fragment.getTabManager(),
                            manager -> getOrCreateActiveTab(manager, url),
                            context.getMainExecutor());
                },
                context.getMainExecutor());
        tabs.put(profileName, created);
        return created;
    }

    @Override
    public void showProfile(String profileName) {
        requireMainThread();
        WebFragment target = fragments.get(profileName);
        if (target == null) {
            visibleProfile = profileName;
            return;
        }
        if (fragmentManager.isStateSaved()) return;

        var tx = fragmentManager.beginTransaction().setReorderingAllowed(true);
        for (Map.Entry<String, WebFragment> entry : fragments.entrySet()) {
            if (entry.getKey().equals(profileName)) tx.show(entry.getValue());
            else tx.hide(entry.getValue());
        }
        tx.commitNow();
        visibleProfile = profileName;
    }

    @Override
    public void closeProfile(String profileName) {
        requireMainThread();
        WebFragment fragment = fragments.remove(profileName);
        tabs.remove(profileName);
        if (fragment != null && !fragmentManager.isStateSaved()) {
            fragmentManager.beginTransaction().remove(fragment).commitNow();
        }
        if (profileName.equals(visibleProfile)) visibleProfile = null;
    }

    @Override
    public void attachFiles(
            String profileName, ListenableFuture<Tab> tab, List<String> uriStrings) {
        throw new UnsupportedOperationException("WebEngine file chooser bridge not wired yet");
    }

    private WebFragment getOrCreateFragment(
            WebSandbox sandbox, String profileName, String persistenceId) {
        WebFragment existing = fragments.get(profileName);
        if (existing != null) return existing;

        String tag = tag(profileName);
        Fragment restored = fragmentManager.findFragmentByTag(tag);
        WebFragment fragment;
        if (restored instanceof WebFragment) {
            fragment = (WebFragment) restored;
        } else {
            FragmentParams params = new FragmentParams.Builder()
                    .setProfileName(profileName)
                    .setPersistenceId(persistenceId)
                    .build();
            fragment = sandbox.createFragment(params);
            fragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .add(containerViewId, fragment, tag)
                    .hide(fragment)
                    .commitNow();
        }
        fragments.put(profileName, fragment);
        if (profileName.equals(visibleProfile)) showProfile(profileName);
        return fragment;
    }

    private ListenableFuture<Tab> getOrCreateActiveTab(TabManager manager, String url) {
        return Futures.transformAsync(
                manager.getActiveTab(),
                active -> {
                    if (active != null) {
                        navigateIfEmpty(active, url);
                        return Futures.immediateFuture(active);
                    }
                    return Futures.transform(
                            manager.createTab(),
                            tab -> {
                                navigateIfEmpty(tab, url);
                                return tab;
                            },
                            context.getMainExecutor());
                },
                context.getMainExecutor());
    }

    private static void navigateIfEmpty(Tab tab, String url) {
        if (tab != null && (tab.getDisplayUri() == null || Uri.EMPTY.equals(tab.getDisplayUri()))) {
            tab.getNavigationController().navigate(url);
        }
    }

    private static String tag(String profileName) {
        return "AIHUB_PROFILE_" + profileName;
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("AI Hub WebEngine host must run on Android main thread");
        }
    }
}
