package com.yagay.aihub.runtime;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.yagay.aihub.browser.RootCdpBridge;

import java.util.concurrent.atomic.AtomicBoolean;

/** Starts the Root CDP forwarder first, then boots the embedded Node browser gateway. */
public final class LocalGatewayManager {
    public interface Listener {
        void onReady();
        void onError(Throwable error);
    }

    private final Context context;
    private final RootCdpBridge cdpBridge;
    private final AtomicBoolean started = new AtomicBoolean(false);

    public LocalGatewayManager(Context context) {
        this.context = context.getApplicationContext();
        this.cdpBridge = new RootCdpBridge(this.context);
    }

    public void start(Listener listener) {
        if (!started.compareAndSet(false, true)) return;
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            try {
                cdpBridge.ensureStarted();
                EmbeddedNodeRuntime.startAsync(context, new EmbeddedNodeRuntime.Listener() {
                    @Override
                    public void onReady() {
                        listener.onReady();
                    }

                    @Override
                    public void onError(Throwable error) {
                        started.set(false);
                        listener.onError(error);
                    }
                });
            } catch (Throwable error) {
                started.set(false);
                main.post(() -> listener.onError(error));
            }
        }, "AIHub-local-gateway").start();
    }
}
