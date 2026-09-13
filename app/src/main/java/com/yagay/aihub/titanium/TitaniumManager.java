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
    private String titaniumDataDir;

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

        assertRoot();

        ApplicationInfo info;
        try {
            info = context.getPackageManager().getApplicationInfo(PACKAGE, 0);
        } catch (Exception error) {
            throw new IllegalStateException("Titanium Browser 未安装（需要包名 " + PACKAGE + "）", error);
        }

        titaniumDataDir = resolveTitaniumDataDir(info);
        extensionDestination = titaniumDataDir + EXTENSION_RELATIVE_PATH;

        File source = new File(context.getFilesDir(), "titanium-extension");
        delete(source);
        if (!source.mkdirs() && !source.isDirectory()) {
            throw new IllegalStateException("无法创建扩展暂存目录");
        }
        copyAssets("titanium-extension", source);

        String expectedBuild = readAssetText("titanium-extension/" + BUILD_ID_FILE).trim();
        if (expectedBuild.isEmpty()) throw new IllegalStateException("APK 内扩展 build marker 缺失");

        String uid = runSu("stat -c %u " + q(titaniumDataDir), 10).trim();
        if (uid.isEmpty()) throw new IllegalStateException("无法读取 Titanium UID：" + titaniumDataDir);

        String installedBuild = runSu("cat " + q(extensionDestination + "/" + BUILD_ID_FILE)
                + " 2>/dev/null || true", 8).trim();
        if (!expectedBuild.equals(installedBuild)) {
            String install = "rm -rf " + q(extensionDestination)
                    + " && mkdir -p " + q(extensionDestination)
                    + " && cp -R " + q(source.getAbsolutePath() + "/.") + " " + q(extensionDestination + "/")
                    + " && chown -R " + uid + ":" + uid + " " + q(extensionDestination)
                    + " && chmod -R u+rwX,go-rwx " + q(extensionDestination)
                    + " && test -f " + q(extensionDestination + "/manifest.json")
                    + " && test -f " + q(extensionDestination + "/" + BUILD_ID_FILE)
                    + " && am force-stop " + PACKAGE;
            runSu(install, 20);
            browserBootstrapped = false;
            launched.clear();
        }

        String installedCheck = runSu("cat " + q(extensionDestination + "/" + BUILD_ID_FILE), 8).trim();
        if (!expectedBuild.equals(installedCheck)) {
            throw new IllegalStateException("Root 已授权，但 Titanium 扩展写入校验失败：" + extensionDestination);
        }
        prepared = true;
    }

    /**
     * ApplicationInfo.dataDir is normally correct, but on some ROMs PackageManager can return
     * a path for user 0 even when the package was installed/initialized in another Android user.
     * Resolve the directory from the filesystem under Root instead of assuming /data/user/0.
     */
    private String resolveTitaniumDataDir(ApplicationInfo info) throws Exception {
        int uidDerivedUser = android.os.Process.myUid() / 100000;
        String declared = info.dataDir == null ? "" : info.dataDir.trim();

        String currentUser = runSu("cmd activity get-current-user 2>/dev/null || am get-current-user 2>/dev/null || echo "
                + uidDerivedUser, 5).trim();
        if (currentUser.isEmpty() || !currentUser.matches("\\d+")) {
            currentUser = String.valueOf(uidDerivedUser);
        }

        StringBuilder probe = new StringBuilder();
        if (!declared.isEmpty()) {
            probe.append("for d in ").append(q(declared)).append(' ');
        } else {
            probe.append("for d in ");
        }
        probe.append(q("/data/user/" + currentUser + "/" + PACKAGE)).append(' ')
                .append(q("/data/data/" + PACKAGE)).append("; do ")
                .append("[ -d \"$d\" ] && { printf '%s' \"$d\"; exit 0; }; done; ")
                .append("exit 0");

        String found = runSu(probe.toString(), 8).trim();
        if (!found.isEmpty()) {
            return found;
        }

        // The package may have just been installed but never launched in this user. Start it once
        // so Android/Chromium can initialize its credential-encrypted data directory, then retry.
        String init = "am start --user " + currentUser
                + " -a android.intent.action.VIEW -d 'about:blank' -p " + PACKAGE
                + " >/dev/null 2>&1 || monkey --user " + currentUser + " -p " + PACKAGE
                + " -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true; sleep 2";
        runSu(init, 8);

        found = runSu(probe.toString(), 8).trim();
        if (!found.isEmpty()) {
            return found;
        }

        // Last-resort diagnostic: find the package under any Android user. We do not silently use
        // another user's private data because AIHub and Titanium could not communicate reliably.
        String anywhere = runSu("for d in /data/user/*/" + PACKAGE + "; do "
                + "[ -d \"$d\" ] && printf '%s\\n' \"$d\"; done", 8).trim();
        if (!anywhere.isEmpty()) {
            throw new IllegalStateException("Titanium 已安装，但不在 AIHub 当前用户 " + currentUser
                    + " 的数据空间。检测到: " + anywhere.replace('\n', ' ')
                    + "。请把 AIHub 与 Titanium 安装在同一个 Android 用户/主空间。");
        }

        throw new IllegalStateException("Titanium 包已安装，但当前用户 " + currentUser
                + " 没有创建应用数据目录。请先手动打开 Titanium 一次，再重新打开 AIHub。"
                + (declared.isEmpty() ? "" : " PackageManager 路径: " + declared));
    }

    private void assertRoot() throws Exception {
        String uid;
        try {
            // The first request may wait for KernelSU/Magisk's approval dialog.
            uid = runSu("id -u", 30).trim();
        } catch (Exception error) {
            throw new IllegalStateException("无法获得 Root：请在 KernelSU/Magisk 中允许 AIHub。详情: "
                    + error.getMessage(), error);
        }
        if (!"0".equals(uid)) {
            throw new IllegalStateException("Root 被拒绝或 su 未返回 uid 0（返回: " + uid + "）");
        }
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
        if (!target.exists() && !target.mkdirs()) throw new IllegalStateException("无法创建 " + target);
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
        java.lang.Process process;
        try {
            process = builder.start();
        } catch (Exception error) {
            throw new IllegalStateException("系统找不到 su 命令", error);
        }

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
            throw new IllegalStateException("Root 命令等待超时");
        }
        reader.join(1000);
        String text = new String(output.toByteArray(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IllegalStateException("su exit=" + process.exitValue() + ": " + text.trim());
        }
        return text;
    }

    private static String q(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
