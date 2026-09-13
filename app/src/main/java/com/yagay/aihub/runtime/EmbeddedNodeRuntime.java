package com.yagay.aihub.runtime;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.res.AssetManager;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;

/** Copies the bundled Node project into app-private storage and starts Node.js Mobile once. */
public final class EmbeddedNodeRuntime {
    public interface Listener {
        void onReady();
        void onError(Throwable error);
    }

    private static final int PORT = 3456;
    private static final AtomicBoolean STARTING = new AtomicBoolean(false);
    private static volatile boolean ready;

    static {
        System.loadLibrary("node");
        System.loadLibrary("aihub_node_runtime");
    }

    private EmbeddedNodeRuntime() {}

    private static native int startNode(String entryPath, String dataDir);

    public static void startAsync(Context context, Listener listener) {
        Context app = context.getApplicationContext();
        Handler main = new Handler(Looper.getMainLooper());
        if (ready || portOpen()) {
            ready = true;
            main.post(listener::onReady);
            return;
        }
        if (!STARTING.compareAndSet(false, true)) {
            new Thread(() -> waitUntilReady(main, listener), "AIHub-gateway-wait").start();
            return;
        }

        new Thread(() -> {
            try {
                File project = prepareProject(app);
                File entry = new File(project, "main.js");
                File dataDir = new File(app.getFilesDir(), "gateway-data");
                if (!dataDir.exists() && !dataDir.mkdirs()) {
                    throw new IOException("Cannot create gateway data directory");
                }

                Thread node = new Thread(() -> {
                    int code = startNode(entry.getAbsolutePath(), dataDir.getAbsolutePath());
                    if (!ready) {
                        main.post(() -> listener.onError(
                                new IllegalStateException("Embedded Node runtime exited with code " + code)));
                    }
                }, "AIHub-node");
                node.start();

                waitUntilReady(main, listener);
            } catch (Throwable error) {
                STARTING.set(false);
                main.post(() -> listener.onError(error));
            }
        }, "AIHub-gateway-start").start();
    }

    private static void waitUntilReady(Handler main, Listener listener) {
        long deadline = System.currentTimeMillis() + 20_000L;
        while (System.currentTimeMillis() < deadline) {
            if (portOpen()) {
                ready = true;
                STARTING.set(false);
                main.post(listener::onReady);
                return;
            }
            try {
                Thread.sleep(150L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        STARTING.set(false);
        main.post(() -> listener.onError(
                new IllegalStateException("Embedded browser gateway did not open localhost:" + PORT)));
    }

    private static boolean portOpen() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", PORT), 250);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static File prepareProject(Context context) throws Exception {
        File target = new File(context.getFilesDir(), "nodejs-project");
        File marker = new File(target, ".apk-version");
        String version = apkVersion(context);
        if (target.isDirectory() && marker.isFile()) {
            String installed = Files.readString(marker.toPath(), StandardCharsets.UTF_8).trim();
            if (version.equals(installed) && new File(target, "main.js").isFile()) return target;
        }

        deleteRecursively(target);
        if (!target.mkdirs() && !target.isDirectory()) {
            throw new IOException("Cannot create Node project directory");
        }
        copyAssetFolder(context.getAssets(), "nodejs-project", target);
        Files.writeString(marker.toPath(), version, StandardCharsets.UTF_8);
        return target;
    }

    private static String apkVersion(Context context) throws Exception {
        PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
        return Long.toString(info.getLongVersionCode());
    }

    private static void copyAssetFolder(AssetManager assets, String assetPath, File target) throws IOException {
        String[] children = assets.list(assetPath);
        if (children == null || children.length == 0) {
            copyAsset(assets, assetPath, target);
            return;
        }
        if (!target.exists() && !target.mkdirs()) {
            throw new IOException("Cannot create " + target);
        }
        for (String child : children) {
            copyAssetFolder(assets, assetPath + "/" + child, new File(target, child));
        }
    }

    private static void copyAsset(AssetManager assets, String assetPath, File target) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Cannot create " + parent);
        }
        try (InputStream input = assets.open(assetPath); FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[32 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) output.write(buffer, 0, count);
            }
        }
    }

    private static void deleteRecursively(File file) throws IOException {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) deleteRecursively(child);
        }
        if (!file.delete()) throw new IOException("Cannot delete " + file);
    }
}
