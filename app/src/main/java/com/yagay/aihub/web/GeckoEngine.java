package com.yagay.aihub.web;

import android.content.Context;

import org.mozilla.geckoview.ContentBlocking;
import org.mozilla.geckoview.GeckoRuntime;
import org.mozilla.geckoview.GeckoRuntimeSettings;
import org.mozilla.geckoview.GeckoSession;
import org.mozilla.geckoview.WebExtension;

import java.util.function.Consumer;

/** Owns the single Gecko runtime and the built-in AIHub bridge extension. */
public final class GeckoEngine {
    private static final String BRIDGE_LOCATION = "resource://android/assets/aihub_bridge/";
    private static final String BRIDGE_ID = "bridge@aihub.local";
    public static final String NATIVE_APP = "aihub";

    private static GeckoRuntime runtime;
    private static WebExtension bridge;

    private GeckoEngine() {}

    public static synchronized GeckoRuntime runtime(Context context) {
        if (runtime != null) return runtime;

        ContentBlocking.Settings blocking = new ContentBlocking.Settings.Builder()
                .cookieBehavior(ContentBlocking.CookieBehavior.ACCEPT_ALL)
                .cookieBehaviorPrivateMode(ContentBlocking.CookieBehavior.ACCEPT_ALL)
                .build();

        GeckoRuntimeSettings settings = new GeckoRuntimeSettings.Builder()
                .javaScriptEnabled(true)
                .contentBlocking(blocking)
                .build();

        runtime = GeckoRuntime.create(context.getApplicationContext(), settings);
        return runtime;
    }

    public static void attachBridge(
            Context context,
            GeckoSession session,
            WebExtension.MessageDelegate delegate,
            Runnable onReady,
            Consumer<Throwable> onError) {
        GeckoRuntime current = runtime(context);
        if (bridge != null) {
            session.getWebExtensionController().setMessageDelegate(bridge, delegate, NATIVE_APP);
            onReady.run();
            return;
        }

        current.getWebExtensionController()
                .ensureBuiltIn(BRIDGE_LOCATION, BRIDGE_ID)
                .accept(extension -> {
                    bridge = extension;
                    session.getWebExtensionController().setMessageDelegate(extension, delegate, NATIVE_APP);
                    onReady.run();
                }, onError::accept);
    }
}
