package com.yagay.aihub.chromium;

import com.yagay.aihub.android.AiHubUiCoordinator;

import org.chromium.chrome.browser.ChromeTabbedActivity;

import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/**
 * One-line hook target for ChromeTabbedActivity.
 *
 * The Chromium patch should call {@code AiHubChromeHook.attach(this)} once from
 * performPostInflationStartup(), after the normal Chrome view hierarchy exists.
 */
public final class AiHubChromeHook {
    private static final WeakHashMap<ChromeTabbedActivity, WeakReference<AiHubUiCoordinator>>
            COORDINATORS = new WeakHashMap<>();

    private AiHubChromeHook() {}

    public static void attach(ChromeTabbedActivity activity) {
        WeakReference<AiHubUiCoordinator> existing = COORDINATORS.get(activity);
        if (existing != null && existing.get() != null) return;

        AiHubChromeBridge bridge = new AiHubChromeBridge(activity);
        AiHubUiCoordinator coordinator = AiHubUiCoordinator.attachDefault(bridge);
        COORDINATORS.put(activity, new WeakReference<>(coordinator));
    }
}
