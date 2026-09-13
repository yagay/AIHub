package com.yagay.aihub.diagnostics;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.yagay.aihub.bridge.LocalBroker;
import com.yagay.aihub.titanium.TitaniumManager;
import com.yagay.aihub.ui.UiHost;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Privacy-conscious one-click diagnostics for AIHub + Titanium integration. */
public final class DiagnosticsExporter {
    public interface Callback {
        void onSuccess(String displayName, String location);
        void onError(Throwable error);
    }

    private static final String TITANIUM_PACKAGE = TitaniumManager.PACKAGE;
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile(
            "(?i)(authorization|cookie|set-cookie|access[_-]?token|refresh[_-]?token|session[_-]?token|bearer)\\s*[:=]\\s*[^\\s,;]+"
    );
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/-]+=*");

    private DiagnosticsExporter() {}

    public static void exportAsync(Context context,
                                   LocalBroker broker,
                                   UiHost ui,
                                   Callback callback) {
        Context app = context.getApplicationContext();
        new Thread(() -> {
            File temp = null;
            try {
                String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
                String displayName = "AIHub-diagnostic-" + stamp + ".zip";
                temp = new File(app.getCacheDir(), displayName);
                if (temp.exists() && !temp.delete()) {
                    throw new IllegalStateException("无法覆盖旧诊断文件");
                }

                String extensionId = readAssetText(app, "titanium-extension/EXTENSION_ID").trim();
                String buildId = readAssetText(app, "titanium-extension/AIHUB_BUILD_ID").trim();

                try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(temp))) {
                    addText(zip, "summary.txt", collectSummary(app, extensionId, buildId));
                    addText(zip, "broker.json", broker == null ? "broker=null\n" : broker.diagnosticSnapshot());
                    addText(zip, "ui.txt", ui == null ? "ui=null\n" : ui.diagnosticSnapshot());
                    addText(zip, "root-basic.txt", collectRootBasic());
                    addText(zip, "titanium-package.txt", collectTitaniumPackage());
                    addText(zip, "titanium-extension.txt", collectTitaniumExtension(extensionId, buildId));
                    addText(zip, "process-network.txt", collectProcessAndNetwork());
                    addText(zip, "logcat.txt", redact(collectLogcat()));
                    addText(zip, "README.txt",
                            "AIHub diagnostic bundle.\n"
                                    + "Root filesystem inspection is executed in PID 1's mount namespace when available, matching TitaniumManager.\n"
                                    + "This bundle intentionally does NOT export browser Cookies, Login Data, Web Data, chat history, provider prompts, or provider responses.\n"
                                    + "Sensitive-looking authorization/token/cookie strings in logcat are redacted best-effort.\n");
                }

                String location = publishToDownloads(app, temp, displayName);
                callback.onSuccess(displayName, location);
            } catch (Throwable error) {
                callback.onError(error);
            } finally {
                if (temp != null && temp.exists()) temp.delete();
            }
        }, "AIHub-Diagnostics").start();
    }

    private static String collectSummary(Context context, String extensionId, String buildId) {
        StringBuilder out = new StringBuilder();
        out.append("generatedAt=").append(new Date()).append('\n');
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
            out.append("aihubPackage=").append(context.getPackageName()).append('\n');
            out.append("aihubVersionName=").append(info.versionName).append('\n');
            if (Build.VERSION.SDK_INT >= 28) out.append("aihubVersionCode=").append(info.getLongVersionCode()).append('\n');
            else out.append("aihubVersionCode=").append(info.versionCode).append('\n');
        } catch (Exception error) {
            out.append("aihubPackageInfoError=").append(error).append('\n');
        }
        out.append("sdk=").append(Build.VERSION.SDK_INT).append('\n');
        out.append("release=").append(Build.VERSION.RELEASE).append('\n');
        out.append("manufacturer=").append(Build.MANUFACTURER).append('\n');
        out.append("brand=").append(Build.BRAND).append('\n');
        out.append("model=").append(Build.MODEL).append('\n');
        out.append("device=").append(Build.DEVICE).append('\n');
        out.append("product=").append(Build.PRODUCT).append('\n');
        out.append("fingerprint=").append(Build.FINGERPRINT).append('\n');
        out.append("processUid=").append(android.os.Process.myUid()).append('\n');
        out.append("extensionId=").append(extensionId).append('\n');
        out.append("extensionBuildId=").append(buildId).append('\n');
        return out.toString();
    }

    private static String collectRootBasic() {
        return rootCommand("id -u; id; getenforce 2>/dev/null || true; uname -a; "
                + "echo current_user=$(cmd activity get-current-user 2>/dev/null || am get-current-user 2>/dev/null || true); "
                + "echo ---props---; getprop ro.build.version.release; getprop ro.build.version.sdk; "
                + "getprop ro.product.manufacturer; getprop ro.product.model; getprop ro.build.fingerprint", 12);
    }

    private static String collectTitaniumPackage() {
        String command = "echo '--- pm path ---'; pm path " + TITANIUM_PACKAGE + " 2>&1; "
                + "echo '--- package fields ---'; dumpsys package " + TITANIUM_PACKAGE
                + " 2>/dev/null | grep -E 'versionName=|versionCode=|userId=|dataDir=|pkg=|flags=' | head -120; "
                + "echo '--- candidate data dirs ---'; for d in /data/user/*/" + TITANIUM_PACKAGE
                + " /data/data/" + TITANIUM_PACKAGE + "; do [ -d \"$d\" ] && { echo \"DIR=$d\"; stat -c 'uid=%u gid=%g mode=%a' \"$d\" 2>/dev/null; "
                + "echo \"CHROME_DIR=$d/app_chrome\"; [ -d \"$d/app_chrome\" ] && stat -c 'chrome_uid=%u chrome_gid=%g chrome_mode=%a' \"$d/app_chrome\" 2>/dev/null || true; }; done; true";
        return rootCommand(command, 15);
    }

    private static String collectTitaniumExtension(String extensionId, String buildId) {
        if (!extensionId.matches("[a-p]{32}")) {
            return "Invalid extension id in APK: " + extensionId + "\n";
        }
        String command = "echo expected_extension_id=" + extensionId + "; echo expected_build_id=" + shell(buildId) + "; "
                + "for d in /data/user/*/" + TITANIUM_PACKAGE + " /data/data/" + TITANIUM_PACKAGE + "; do "
                + "[ -d \"$d\" ] || continue; echo; echo \"=== DATA_DIR $d ===\"; "
                + "e=\"$d/app_chrome/extensions\"; echo \"external_dir=$e\"; ls -lan \"$e\" 2>&1 || true; "
                + "j=\"$e/" + extensionId + ".json\"; c=\"$e/" + extensionId + ".crx\"; b=\"$e/" + extensionId + ".aihub-build\"; "
                + "echo '--- external json ---'; [ -f \"$j\" ] && cat \"$j\" || echo missing; "
                + "echo; echo '--- crx ---'; [ -f \"$c\" ] && { stat -c 'size=%s uid=%u gid=%g mode=%a' \"$c\"; echo -n 'magic='; od -An -tx1 -N4 \"$c\"; sha256sum \"$c\" 2>/dev/null || true; } || echo missing; "
                + "echo '--- build marker ---'; if [ -f \"$b\" ]; then actual_build=$(cat \"$b\" 2>/dev/null); echo \"$actual_build\"; [ \"$actual_build\" = " + shell(buildId) + " ] && echo build_match=yes || echo build_match=no; else echo missing; echo build_match=no; fi; "
                + "echo; echo '--- installed extension profiles ---'; "
                + "for p in \"$d/app_chrome\"/*; do [ -d \"$p\" ] || continue; x=\"$p/Extensions/" + extensionId + "\"; [ -d \"$x\" ] || continue; "
                + "echo \"PROFILE=$(basename \"$p\")\"; find \"$x\" -maxdepth 3 -type f -print 2>/dev/null | head -120; "
                + "for m in \"$x\"/*/manifest.json; do [ -f \"$m\" ] && { echo \"manifest_file=$m\"; grep -m1 -E '\"version\"[[:space:]]*:' \"$m\" 2>/dev/null || true; }; done; "
                + "for w in \"$x\"/*/service-worker.js; do [ -f \"$w\" ] && { grep -q 'AIHUB_BRIDGE_WORKER_V3' \"$w\" 2>/dev/null && echo worker_marker=AIHUB_BRIDGE_WORKER_V3 || echo worker_marker=old_or_missing; }; done; done; "
                + "echo '--- profile files containing extension id (filenames only) ---'; "
                + "for p in \"$d/app_chrome/Local State\" \"$d/app_chrome\"/*/Preferences \"$d/app_chrome\"/*/'Secure Preferences'; do "
                + "[ -f \"$p\" ] && grep -q -F '" + extensionId + "' \"$p\" 2>/dev/null && echo \"contains_id=$p\"; done; "
                + "done; true";
        return rootCommand(command, 25);
    }

    private static String collectProcessAndNetwork() {
        String command = "echo '--- processes ---'; ps -A -o USER,PID,PPID,NAME,ARGS 2>/dev/null | grep -E '(^| )com\\.yagay\\.aihub|io\\.github\\.jqssun\\.helium|chromium' | head -120 || true; "
                + "echo '--- port 3847 ---'; ss -ltnp 2>/dev/null | grep ':3847' || netstat -ltnp 2>/dev/null | grep ':3847' || true; "
                + "echo '--- established 3847 ---'; ss -tnp 2>/dev/null | grep ':3847' || true";
        return rootCommand(command, 12);
    }

    private static String collectLogcat() {
        String command = "logcat -d -t 2500 2>/dev/null | "
                + "grep -Ei 'AIHub|Titanium|helium|chromium|extension|3847|WebSocket|LocalBroker' | tail -1000";
        return rootCommand(command, 20);
    }

    private static String rootCommand(String command, int timeoutSeconds) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        java.lang.Process process = null;
        try {
            String inner = shell(command);
            String namespaced = "if command -v nsenter >/dev/null 2>&1 && [ -r /proc/1/ns/mnt ]; then "
                    + "echo namespace=pid1-nsenter; nsenter -t 1 -m sh -c " + inner
                    + "; elif toybox nsenter --help >/dev/null 2>&1 && [ -r /proc/1/ns/mnt ]; then "
                    + "echo namespace=pid1-toybox-nsenter; toybox nsenter -t 1 -m sh -c " + inner
                    + "; else echo namespace=caller-fallback; sh -c " + inner + "; fi";
            process = new ProcessBuilder("su", "-c", namespaced).redirectErrorStream(true).start();
            java.lang.Process p = process;
            Thread reader = new Thread(() -> {
                try (InputStream in = p.getInputStream()) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = in.read(buffer)) >= 0) if (count > 0) output.write(buffer, 0, count);
                } catch (Exception ignored) {}
            }, "AIHub-Diagnostic-root-reader");
            reader.start();
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                reader.join(1000);
                return "TIMEOUT after " + timeoutSeconds + "s\n" + new String(output.toByteArray(), StandardCharsets.UTF_8);
            }
            reader.join(1000);
            return "exit=" + process.exitValue() + "\n" + new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception error) {
            if (process != null) process.destroyForcibly();
            return "ERROR: " + error + "\n" + new String(output.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private static String readAssetText(Context context, String path) {
        try (InputStream in = context.getAssets().open(path); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = in.read(buffer)) >= 0) if (count > 0) out.write(buffer, 0, count);
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Exception error) {
            return "<asset-error:" + error.getMessage() + ">";
        }
    }

    private static void addText(ZipOutputStream zip, String name, String text) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static String publishToDownloads(Context context, File source, String displayName) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/zip");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("无法创建 Downloads 文件");
            boolean success = false;
            try (OutputStream out = resolver.openOutputStream(uri); FileInputStream in = new FileInputStream(source)) {
                if (out == null) throw new IllegalStateException("无法打开 Downloads 输出流");
                copy(in, out);
                success = true;
            } finally {
                if (success) {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.Downloads.IS_PENDING, 0);
                    resolver.update(uri, done, null, null);
                } else {
                    resolver.delete(uri, null, null);
                }
            }
            return Environment.DIRECTORY_DOWNLOADS + "/" + displayName;
        }

        File dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) throw new IllegalStateException("外部 Download 目录不可用");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("无法创建诊断目录");
        File target = new File(dir, displayName);
        try (FileInputStream in = new FileInputStream(source); FileOutputStream out = new FileOutputStream(target)) {
            copy(in, out);
        }
        return target.getAbsolutePath();
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[32768];
        int count;
        while ((count = in.read(buffer)) >= 0) if (count > 0) out.write(buffer, 0, count);
        out.flush();
    }

    private static String redact(String input) {
        if (input == null || input.isEmpty()) return "";
        String redacted = SECRET_ASSIGNMENT.matcher(input).replaceAll("$1=<redacted>");
        return BEARER.matcher(redacted).replaceAll("Bearer <redacted>");
    }

    private static String shell(String value) {
        String safe = value == null ? "" : value;
        return "'" + safe.replace("'", "'\\''") + "'";
    }
}
