package com.yagay.aihub.xposed;

import android.os.Build;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.yagay.aihub.titanium.TitaniumManager;

import java.lang.reflect.Method;

import io.github.libxposed.api.XposedModule;

/** LSPosed API 102 fallback that injects AIHub's unpacked extension into Titanium. */
public final class TitaniumXposedModule extends XposedModule {
    private static final String TAG = "AIHub-Titanium";

    @Override
    @RequiresApi(Build.VERSION_CODES.Q)
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        if (!TitaniumManager.PACKAGE.equals(param.getPackageName())) return;

        try {
            ClassLoader loader = param.getDefaultClassLoader();
            Class<?> commandLine = Class.forName("org.chromium.base.CommandLine", false, loader);
            Method init = commandLine.getDeclaredMethod("init", String[].class);
            Method switchToNative = commandLine.getDeclaredMethod("switchToNativeImpl");

            hook(init).setPriority(PRIORITY_HIGHEST).intercept(chain -> {
                Object result = chain.proceed();
                inject(commandLine);
                return result;
            });

            hook(switchToNative).setPriority(PRIORITY_HIGHEST).intercept(chain -> {
                inject(commandLine);
                return chain.proceed();
            });

            // If Chromium initialized unusually early, inject immediately as well.
            try {
                Method initialized = commandLine.getDeclaredMethod("isInitialized");
                if (Boolean.TRUE.equals(initialized.invoke(null))) inject(commandLine);
            } catch (Throwable ignored) {}

            log(Log.INFO, TAG, "Titanium CommandLine hooks installed");
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Failed to install Titanium extension hook", error);
        }
    }

    private void inject(Class<?> commandLine) throws Exception {
        Method getInstance = commandLine.getDeclaredMethod("getInstance");
        Object instance = getInstance.invoke(null);
        Method getSwitchValue = commandLine.getDeclaredMethod("getSwitchValue", String.class);
        Method removeSwitch = commandLine.getDeclaredMethod("removeSwitch", String.class);
        Method appendSwitch = commandLine.getDeclaredMethod("appendSwitchWithValue", String.class, String.class);

        String existing = (String) getSwitchValue.invoke(instance, "load-extension");
        String destination = TitaniumManager.EXTENSION_DEST;
        if (existing != null && existing.contains(destination)) return;

        String merged = existing == null || existing.isBlank()
                ? destination
                : existing + "," + destination;
        if (existing != null) removeSwitch.invoke(instance, "load-extension");
        appendSwitch.invoke(instance, "load-extension", merged);
        log(Log.INFO, TAG, "Injected AIHub extension path into Titanium");
    }
}
