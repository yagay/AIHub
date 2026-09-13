package com.yagay.aihub.titanium;

import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Root-side Titanium integration.
 *
 * Root stages/updates the unpacked extension inside Titanium's private data directory.
 * LSPosed injects --load-extension on every Titanium start when the module is enabled.
 * For devices where the LSPosed scope is not enabled yet, AIHub also performs a safe,
 * temporary chrome-command-line bootstrap only while starting Titanium itself.
 */
public final class TitaniumManager {
    public interface Callback { void onReady(); void onError(Throwable error); }

    public static final String PACKAGE = "io.github.jqssun.helium";
    public static final String EXTENSION_RELATIVE_PATH = "/files/aihub-extension";
    private static final String BUILD_ID_FILE = "AIHUB_BUILD_ID";
    private static final String COMMAND_LINE = "/data/local/tmp/chrome-command-line";
    private static final String[] PROVIDERS = {"chatgpt", "claude", "gemini", "deepseek", "grok"};

    private final Context context;
    private final Set<String> launched = new HashSet<>();
    private volatile boolean prepared;
    private volatile boolean browserBootstrapped;
    private String extensionDestination;

    public TitaniumManager(Context context) {
        this.context = context.getApplicationContext();
    }

    public void prepareAsync(Callback callback) {
        new Thread(() -> {
            try {
                prepare();
                callback.onReady();
            } catch (Throwable error) {
                callback.onError(error);
            }
        }, "AIHub-Titanium-prepare").start();
    }

    public synchronized void prepare() throws Exception {
        if (prepared) return;
        ApplicationInfo info = context.getPackageManager().getApplicationInfo(PACKAGE, 0);
        if (info.dataDir == null || info.dataDir.trim().isEmpty()) {
            throw new IllegalStateException("Cannot resolve Titanium data directory");
        }
        extensionDestination = info.dataDir + EXTENSION_RELATIVE_PATH;

        File source = new File(context.getFilesDir(), "titanium-extension");
        delete(source);
        if (!source.mkdirs() && !source.isDirectory()) {
            throw new IllegalStateException("Cannot create extension staging directory");
        }
        copyAssets("titanium-extension", source);

        String expectedBuild = readAssetText("titanium-extension/" + BUILD_ID_FILE).trim();
        if (expectedBuild.isEmpty()) throw new IllegalStateException("Extension build marker missing");

        String uid = runSu("stat -c %u " + q(info.dataDir), 8).trim();
        if (uid.isEmpty()) throw new IllegalStateException("Cannot resolve Titanium UID");

        String installedBuild = runSu("cat " + q(extensionDestination + "/" + BUILD_ID_FILE) + " 2>/dev/null || true", 5).trim();
        if (!expectedBuild.equals(installedBuild)) {
            String install = "rm -rf " + q(extensionDestination)
                    + " && mkdir -p " + q(extensionDestination)
                    + " && cp -R " + q(source.getAbsolutePath() + "/.") + " " + q(extensionDestination + "/")
                    + " && chown -R " + uid + ":" + uid + " " + q(extensionDestination)
                    + " && chmod -R u+rwX,go-rwx " + q(extensionDestination)
                    + " && am force-stop " + PACKAGE;
            runSu(install, 15);
            browserBootstrapped = false;
            launched.clear();
        }
        prepared = true;
    }

    public synchronized void launchProvider(String provider) {
        if (!isProvider(provider)) return;
        new Thread(() -> {
            try {
                prepare();
                synchronized (TitaniumManager.this) {
                    if (!launched.add(provider)) return;
                }
                String url = providerUrl(provider);
                if (!browserBootstrapped) {
                    bootstrapTitanium(url);
                    browserBootstrapped = true;
                } else {
                    openProvider(url);
                }
            } catch (Throwable ignored) {}
        }, "AIHub-open-" + provider).start();
    }

    /**
     * Temporary Root fallback for the first Titanium process start.
     * The global Chromium command-line file is restored immediately after Titanium initializes,
     * so other Chromium browsers are not left with AIHub's --load-extension switch.
     */
    private void bootstrapTitanium(String url) throws Exception {
        String original = runSu("cat " + q(COMMAND_LINE) + " 2>/dev/null || true", 5).trim();
        String merged = mergeLoadExtension(original, extensionDestination);
        String backup = "/data/local/tmp/aihub-chrome-command-line-" + android.os.Process.myUid() + ".bak";

        String command = "if [ -f " + q(COMMAND_LINE) + " ]; then cp -p " + q(COMMAND_LINE) + " " + q(backup)
                + "; else rm -f " + q(backup) + "; fi"
                + " ; printf %s " + q(merged) + " > " + q(COMMAND_LINE)
                + " ; chmod 644 " + q(COMMAND_LINE)
                + " ; am force-stop " + PACKAGE
                + " ; am start -W -a android.intent.action.VIEW -d " + q(url) + " -p " + PACKAGE + " >/dev/null 2>&1 || true"
                + " ; sleep 2"
                + " ; if [ -f " + q(backup) + " ]; then mv " + q(backup) + " " + q(COMMAND_LINE)
                + "; else rm -f " + q(COMMAND_LINE) + "; fi"
                + " ; am start --activity-reorder-to-front -n com.yagay.aihub/com.yagay.aihub.app.MainActivity >/dev/null 2>&1 || true";
        runSu(command, 15);
    }

    private void openProvider(String url) throws Exception {
        String command = "am start -a android.intent.action.VIEW -d " + q(url)
                + " -p " + PACKAGE + " >/dev/null 2>&1 || true"
                + " ; sleep 1"
                + " ; am start --activity-reorder-to-front -n com.yagay.aihub/com.yagay.aihub.app.MainActivity >/dev/null 2>&1 || true";
        runSu(command, 10);
    }

    private static String mergeLoadExtension(String original, String destination) {
        String value = original == null ? "" : original.trim();
        if (value.isEmpty()) value = "_";
        value = value.replaceAll("\\s+--load-extension=(?:'[^']*'|\"[^\"]*\"|\\S*aihub-extension\\S*)", "").trim();
        if (!value.startsWith("_")) value = "_ " + value;
        return value + " --load-extension=" + destination;
    }

    private static boolean isProvider(String provider) {
        for (String value : PROVIDERS) if (value.equals(provider)) return true;
        return false;
    }

    private static String providerUrl(String provider) {
        return switch (provider) {
            case "claude" -> "https://claude.ai/new";
            case "gemini" -> "https://gemini.google.com/app";
            case "deepseek" -> "https://chat.deepseek.com/";
            case "grok" -> "https://grok.com/";
            default -> "https://chatgpt.com/";
        };
    }

    private String readAssetText(String path) throws Exception {
        try (InputStream in = context.getAssets().open(path); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) >= 0) if (count > 0) out.write(buffer, 0, count);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void copyAssets(String path, File target) throws Exception {
        String[] children = context.getAssets().list(path);
        if (children == null || children.length == 0) {
            try (InputStream in = context.getAssets().open(path); FileOutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[32768];
                int count;
                while ((count = in.read(buffer)) >= 0) if (count > 0) out.write(buffer, 0, count);
            }
            return;
        }
        if (!target.exists() && !target.mkdirs()) throw new IllegalStateException("Cannot create " + target);
        for (String child : children) copyAssets(path + "/" + child, new File(target, child));
    }

    private static void delete(File file) {
        if (!file.exists()) return;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) delete(child);
        file.delete();
    }

    private static String runSu(String command, int timeoutSeconds) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("su", "-c", command);
        builder.redirectErrorStream(true);
        java.lang.Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) >= 0) if (count > 0) output.write(buffer, 0, count);
            } catch (Exception ignored) {}
        });
        reader.start();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException("Root command timed out");
        }
        reader.join(1000);
        String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) throw new IllegalStateException("Root failed: " + text.trim());
        return text;
    }

    private static String q(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
