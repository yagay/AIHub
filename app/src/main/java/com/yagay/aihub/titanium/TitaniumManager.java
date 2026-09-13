package com.yagay.aihub.titanium;

import android.content.Context;
import android.content.pm.PackageManager;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Installs the unpacked AIHub extension into Titanium using Chromium's rooted command-line path. */
public final class TitaniumManager {
    public interface Callback { void onReady(); void onError(Throwable error); }

    private static final String PACKAGE = "io.github.jqssun.helium";
    private static final String DEST = "/data/user/0/" + PACKAGE + "/files/aihub-extension";
    private static final String COMMAND_LINE = "/data/local/chrome-command-line";
    private static final String[] PROVIDERS = {"chatgpt", "claude", "gemini", "deepseek", "grok"};

    private final Context context;
    private final Set<String> launched = new HashSet<>();
    private volatile boolean prepared;

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
        context.getPackageManager().getPackageInfo(PACKAGE, 0);

        File source = new File(context.getFilesDir(), "titanium-extension");
        delete(source);
        if (!source.mkdirs() && !source.isDirectory()) throw new IllegalStateException("Cannot create extension staging directory");
        copyAssets("titanium-extension", source);

        String uid = runSu("stat -c %u /data/user/0/" + PACKAGE, 8).trim();
        if (uid.isEmpty()) throw new IllegalStateException("Cannot resolve Titanium UID");

        String existing = runSu("cat " + COMMAND_LINE + " 2>/dev/null || true", 5).trim();
        if (existing.isEmpty()) existing = "_";
        existing = existing.replaceAll("\\s+--load-extension=\\S*aihub-extension\\S*", "").trim();
        if (!existing.startsWith("_")) existing = "_ " + existing;
        String commandLine = existing + " --load-extension=" + DEST;

        String command = "rm -rf " + q(DEST)
                + " && mkdir -p " + q(DEST)
                + " && cp -R " + q(source.getAbsolutePath() + "/.") + " " + q(DEST + "/")
                + " && chown -R " + uid + ":" + uid + " " + q(DEST)
                + " && chmod -R u+rwX,go-rwx " + q(DEST)
                + " && printf %s " + q(commandLine) + " > " + q(COMMAND_LINE)
                + " && chmod 644 " + q(COMMAND_LINE)
                + " && am force-stop " + PACKAGE;
        runSu(command, 15);
        prepared = true;
    }

    public synchronized void launchProvider(String provider) {
        if (!isProvider(provider)) return;
        new Thread(() -> {
            try {
                prepare();
                if (!launched.add(provider)) return;
                String url = providerUrl(provider);
                String cmd = "am start -a android.intent.action.VIEW -d " + q(url)
                        + " -p " + PACKAGE + " >/dev/null 2>&1"
                        + " ; sleep 1"
                        + " ; am start --activity-reorder-to-front -n com.yagay.aihub/com.yagay.aihub.app.MainActivity >/dev/null 2>&1";
                runSu(cmd, 10);
            } catch (Throwable ignored) {}
        }, "AIHub-open-" + provider).start();
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

    private static String q(String value) { return "'" + value.replace("'", "'\\''") + "'"; }
}
