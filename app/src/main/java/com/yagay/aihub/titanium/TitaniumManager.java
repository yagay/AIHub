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
 * Titanium patches Chromium's DIR_EXTERNAL_EXTENSIONS to use DIR_USER_DATA/extensions.
 * On Android DIR_USER_DATA is the app-specific app_chrome directory, so AIHub installs
 * a stable CRX3 plus its standalone external-extension JSON into:
 *   <Titanium dataDir>/app_chrome/extensions/
 *
 * Titanium then installs and persists the extension itself. No --load-extension or
 * Chromium CommandLine hook is required.
 */
public final class TitaniumManager {
    public interface Callback { void onReady(); void onError(Throwable error); }

    public static final String PACKAGE = "io.github.jqssun.helium";
    private static final String BUILD_ID_FILE = "AIHUB_BUILD_ID";
    private static final String EXTENSION_ID_FILE = "EXTENSION_ID";
    private static final String CHROME_USER_DATA_RELATIVE = "/app_chrome";
    private static final String EXTERNAL_EXTENSIONS_RELATIVE = "/app_chrome/extensions";
    private static final String OLD_UNPACKED_RELATIVE = "/files/aihub-extension";
    private static final String[] PROVIDERS = {"chatgpt", "claude", "gemini", "deepseek", "grok"};

    private final Context context;
    private final Set<String> launched = new HashSet<>();
    private volatile boolean prepared;
    private String titaniumDataDir;
    private String externalExtensionsDir;
    private String extensionId;
    private String extensionBuildId;

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
        String uid = runSu("stat -c %u " + q(titaniumDataDir), 10).trim();
        if (uid.isEmpty()) throw new IllegalStateException("无法读取 Titanium UID：" + titaniumDataDir);

        ensureChromiumUserData(uid);
        externalExtensionsDir = titaniumDataDir + EXTERNAL_EXTENSIONS_RELATIVE;

        File source = new File(context.getFilesDir(), "titanium-extension");
        delete(source);
        if (!source.mkdirs() && !source.isDirectory()) {
            throw new IllegalStateException("无法创建扩展暂存目录");
        }
        copyAssets("titanium-extension", source);

        String expectedBuild = readAssetText("titanium-extension/" + BUILD_ID_FILE).trim();
        extensionBuildId = expectedBuild;
        extensionId = readAssetText("titanium-extension/" + EXTENSION_ID_FILE).trim();
        if (expectedBuild.isEmpty()) throw new IllegalStateException("APK 内扩展 build marker 缺失");
        if (!extensionId.matches("[a-p]{32}")) {
            throw new IllegalStateException("APK 内扩展 ID 无效：" + extensionId);
        }

        File sourceCrx = new File(source, extensionId + ".crx");
        File sourceJson = new File(source, extensionId + ".json");
        if (!sourceCrx.isFile() || sourceCrx.length() < 16 || !sourceJson.isFile()) {
            throw new IllegalStateException("APK 内缺少 Titanium external-extension CRX/JSON");
        }

        String buildMarker = externalExtensionsDir + "/" + extensionId + ".aihub-build";
        String installedBuild = runSu("cat " + q(buildMarker) + " 2>/dev/null || true", 8).trim();
        String installedCrx = externalExtensionsDir + "/" + extensionId + ".crx";
        String installedJson = externalExtensionsDir + "/" + extensionId + ".json";
        String installedFilesOk = runSu("[ -s " + q(installedCrx) + " ] && [ -s " + q(installedJson)
                + " ] && echo yes || true", 5).trim();

        boolean extensionFilesChanged = !expectedBuild.equals(installedBuild) || !"yes".equals(installedFilesOk);
        if (extensionFilesChanged) {
            String install = "mkdir -p " + q(externalExtensionsDir)
                    + " && cp " + q(sourceCrx.getAbsolutePath()) + " " + q(installedCrx)
                    + " && cp " + q(sourceJson.getAbsolutePath()) + " " + q(installedJson)
                    + " && cp " + q(new File(source, BUILD_ID_FILE).getAbsolutePath()) + " " + q(buildMarker)
                    + " && chown " + uid + ":" + uid + " " + q(externalExtensionsDir)
                    + " && chown " + uid + ":" + uid + " " + q(installedCrx) + " " + q(installedJson) + " " + q(buildMarker)
                    + " && chmod 700 " + q(externalExtensionsDir)
                    + " && chmod 600 " + q(installedCrx) + " " + q(installedJson) + " " + q(buildMarker)
                    + " && rm -rf " + q(titaniumDataDir + OLD_UNPACKED_RELATIVE)
                    + " && am force-stop " + PACKAGE;
            runSu(install, 20);
            launched.clear();
        }

        String installedCheck = runSu("cat " + q(buildMarker) + " 2>/dev/null || true", 8).trim();
        String fileCheck = runSu("[ -s " + q(installedCrx) + " ] && [ -s " + q(installedJson)
                + " ] && echo yes || true", 5).trim();
        if (!expectedBuild.equals(installedCheck) || !"yes".equals(fileCheck)) {
            throw new IllegalStateException("Root 已授权，但 Titanium External Extension 写入校验失败："
                    + externalExtensionsDir);
        }

        ensureTitaniumExtensionRegistered();
        if (extensionFilesChanged) {
            // Chromium may unpack an external-extension update while the old MV3
            // worker remains alive until the next browser process start. Restart
            // once more after the exact build appears in the profile so the newly
            // installed service worker/content script become the active runtime.
            restartTitaniumForExtensionActivation();
        }
        prepared = true;
    }

    /**
     * External-extension files are consumed by Chromium when Titanium starts. A first
     * install/update can leave the browser force-stopped with valid CRX/JSON files but
     * no profile registration. Cold-start Titanium once and wait for Chromium to
     * persist the extension into its profile before reporting the bridge as ready.
     */
    private void ensureTitaniumExtensionRegistered() throws Exception {
        if (isExtensionRegistered()) return;

        int uidDerivedUser = android.os.Process.myUid() / 100000;
        String currentUser = runSu("cmd activity get-current-user 2>/dev/null || am get-current-user 2>/dev/null || echo "
                + uidDerivedUser, 5).trim();
        if (currentUser.isEmpty() || !currentUser.matches("\\d+")) {
            currentUser = String.valueOf(uidDerivedUser);
        }

        String launch = "am force-stop " + PACKAGE
                + "; sleep 1; monkey --user " + currentUser + " -p " + PACKAGE
                + " -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1"
                + " || am start --user " + currentUser
                + " -a android.intent.action.VIEW -d 'https://chatgpt.com/' -p " + PACKAGE
                + " >/dev/null 2>&1 || true";
        runSu(launch, 10);

        for (int attempt = 0; attempt < 10; attempt++) {
            if (isExtensionRegistered()) {
                runSu("am start --activity-reorder-to-front -n com.yagay.aihub/com.yagay.aihub.app.MainActivity"
                        + " >/dev/null 2>&1 || true", 5);
                launched.clear();
                return;
            }
            Thread.sleep(1000);
        }

        runSu("am start --activity-reorder-to-front -n com.yagay.aihub/com.yagay.aihub.app.MainActivity"
                + " >/dev/null 2>&1 || true", 5);
        throw new IllegalStateException(
                "Root 与扩展文件均正常，但 Titanium 启动后仍未注册 AIHub Bridge。"
                        + "请打开 Titanium 的 chrome://extensions 检查扩展加载状态。");
    }

    private void restartTitaniumForExtensionActivation() throws Exception {
        int uidDerivedUser = android.os.Process.myUid() / 100000;
        String currentUser = runSu("cmd activity get-current-user 2>/dev/null || am get-current-user 2>/dev/null || echo "
                + uidDerivedUser, 5).trim();
        if (currentUser.isEmpty() || !currentUser.matches("\\d+")) {
            currentUser = String.valueOf(uidDerivedUser);
        }

        String restart = "am force-stop " + PACKAGE
                + "; sleep 1; monkey --user " + currentUser + " -p " + PACKAGE
                + " -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1"
                + " || am start --user " + currentUser
                + " -a android.intent.action.VIEW -d 'https://chatgpt.com/' -p " + PACKAGE
                + " >/dev/null 2>&1 || true";
        runSu(restart, 12);
        Thread.sleep(1500);
        runSu("am start --activity-reorder-to-front -n com.yagay.aihub/com.yagay.aihub.app.MainActivity"
                + " >/dev/null 2>&1 || true", 5);
        launched.clear();
    }

    private boolean isExtensionRegistered() throws Exception {
        if (extensionBuildId == null || extensionBuildId.isEmpty()) return false;
        String command = "for x in " + q(titaniumDataDir + "/app_chrome")
                + "/*/Extensions/" + extensionId + "/*/" + BUILD_ID_FILE + "; do "
                + "[ -f \"$x\" ] && [ \"$(cat \"$x\" 2>/dev/null)\" = " + q(extensionBuildId)
                + " ] && { echo yes; exit 0; }; done; true";
        return "yes".equals(runSu(command, 8).trim());
    }

    private void ensureChromiumUserData(String uid) throws Exception {
        String userData = titaniumDataDir + CHROME_USER_DATA_RELATIVE;
        String exists = runSu("[ -d " + q(userData) + " ] && echo yes || true", 5).trim();
        if ("yes".equals(exists)) return;

        int uidDerivedUser = android.os.Process.myUid() / 100000;
        String currentUser = runSu("cmd activity get-current-user 2>/dev/null || am get-current-user 2>/dev/null || echo "
                + uidDerivedUser, 5).trim();
        if (currentUser.isEmpty() || !currentUser.matches("\\d+")) currentUser = String.valueOf(uidDerivedUser);

        String init = "am start --user " + currentUser
                + " -a android.intent.action.VIEW -d 'about:blank' -p " + PACKAGE
                + " >/dev/null 2>&1 || monkey --user " + currentUser + " -p " + PACKAGE
                + " -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true; sleep 2";
        runSu(init, 8);

        exists = runSu("[ -d " + q(userData) + " ] && echo yes || true", 5).trim();
        if (!"yes".equals(exists)) {
            runSu("mkdir -p " + q(userData) + " && chown " + uid + ":" + uid + " " + q(userData)
                    + " && chmod 700 " + q(userData), 8);
        }
    }

    /** Resolve Titanium's app data directory for the current Android user under Root. */
    private String resolveTitaniumDataDir(ApplicationInfo info) throws Exception {
        int uidDerivedUser = android.os.Process.myUid() / 100000;
        String declared = info.dataDir == null ? "" : info.dataDir.trim();

        String currentUser = runSu("cmd activity get-current-user 2>/dev/null || am get-current-user 2>/dev/null || echo "
                + uidDerivedUser, 5).trim();
        if (currentUser.isEmpty() || !currentUser.matches("\\d+")) {
            currentUser = String.valueOf(uidDerivedUser);
        }

        StringBuilder probe = new StringBuilder();
        if (!declared.isEmpty()) probe.append("for d in ").append(q(declared)).append(' ');
        else probe.append("for d in ");
        probe.append(q("/data/user/" + currentUser + "/" + PACKAGE)).append(' ')
                .append(q("/data/data/" + PACKAGE)).append("; do ")
                .append("[ -d \"$d\" ] && { printf '%s' \"$d\"; exit 0; }; done; exit 0");

        String found = runSu(probe.toString(), 8).trim();
        if (!found.isEmpty()) return found;

        String init = "am start --user " + currentUser
                + " -a android.intent.action.VIEW -d 'about:blank' -p " + PACKAGE
                + " >/dev/null 2>&1 || monkey --user " + currentUser + " -p " + PACKAGE
                + " -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || true; sleep 2";
        runSu(init, 8);

        found = runSu(probe.toString(), 8).trim();
        if (!found.isEmpty()) return found;

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
            uid = runSuRaw("id -u", 30).trim();
        } catch (Exception error) {
            throw new IllegalStateException("无法获得 Root：请在 KernelSU / KernelSU Next / Magisk 中允许 AIHub。详情: "
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
                openProvider(providerUrl(provider));
            } catch (Throwable ignored) {}
        }, "AIHub-open-" + provider).start();
    }

    private void openProvider(String url) throws Exception {
        String command = "am start -a android.intent.action.VIEW -d " + q(url)
                + " -p " + PACKAGE + " >/dev/null 2>&1 || true"
                + " ; sleep 1"
                + " ; am start --activity-reorder-to-front -n com.yagay.aihub/com.yagay.aihub.app.MainActivity >/dev/null 2>&1 || true";
        runSu(command, 10);
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

    /**
     * KernelSU/Magisk may grant uid 0 while still leaving the app in its own mount
     * namespace. Root authorization and mount-namespace access are intentionally
     * detected separately so a broken nsenter implementation is never reported as
     * "no root".
     */
    private static volatile String namespaceMode;

    private static String runSuRaw(String command, int timeoutSeconds) throws Exception {
        java.lang.Process process = null;
        Exception startError = null;
        String[] candidates = {"su", "/system/bin/su", "/system/xbin/su", "/sbin/su"};
        for (String candidate : candidates) {
            try {
                process = new ProcessBuilder(candidate, "-c", command)
                        .redirectErrorStream(true)
                        .start();
                break;
            } catch (Exception error) {
                startError = error;
            }
        }
        if (process == null) {
            throw new IllegalStateException("系统找不到可用的 su 命令", startError);
        }

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        java.lang.Process runningProcess = process;
        Thread reader = new Thread(() -> {
            try (InputStream in = runningProcess.getInputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) >= 0) if (count > 0) output.write(buffer, 0, count);
            } catch (Exception ignored) {}
        }, "AIHub-su-reader");
        reader.start();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            reader.join(1000);
            throw new IllegalStateException("Root 命令等待超时");
        }
        reader.join(1000);
        String result = new String(output.toByteArray(), StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new IllegalStateException("su exit=" + process.exitValue() + ": " + result.trim());
        }
        return result;
    }

    private static String resolveNamespaceMode() throws Exception {
        String cached = namespaceMode;
        if (cached != null) return cached;
        synchronized (TitaniumManager.class) {
            if (namespaceMode != null) return namespaceMode;

            String probe = runSuRaw(
                    "if command -v nsenter >/dev/null 2>&1 && [ -r /proc/1/ns/mnt ]; then "
                            + "nsenter -t 1 -m -- sh -c 'printf AIHUB_NS_OK' 2>/dev/null || true; fi",
                    8).trim();
            if ("AIHUB_NS_OK".equals(probe)) {
                namespaceMode = "nsenter";
                return namespaceMode;
            }

            probe = runSuRaw(
                    "if toybox nsenter --help >/dev/null 2>&1 && [ -r /proc/1/ns/mnt ]; then "
                            + "toybox nsenter -t 1 -m -- sh -c 'printf AIHUB_NS_OK' 2>/dev/null || true; fi",
                    8).trim();
            if ("AIHUB_NS_OK".equals(probe)) {
                namespaceMode = "toybox";
                return namespaceMode;
            }

            namespaceMode = "caller";
            return namespaceMode;
        }
    }

    private static String runSu(String command, int timeoutSeconds) throws Exception {
        String inner = q(command);
        String mode = resolveNamespaceMode();
        String wrapped;
        if ("nsenter".equals(mode)) {
            wrapped = "nsenter -t 1 -m -- sh -c " + inner;
        } else if ("toybox".equals(mode)) {
            wrapped = "toybox nsenter -t 1 -m -- sh -c " + inner;
        } else {
            wrapped = "sh -c " + inner;
        }
        return runSuRaw(wrapped, timeoutSeconds);
    }

    private static String q(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
