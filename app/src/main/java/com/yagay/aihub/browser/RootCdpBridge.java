package com.yagay.aihub.browser;

import android.content.Context;
import android.os.Process;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Root-only tcp:9222 -> Chrome localabstract DevTools socket forwarder. */
public final class RootCdpBridge {
    public static final int PORT = 9222;
    private static final String ASSET = "native/aihub_cdp_forwarder";

    private final Context context;

    public RootCdpBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    public synchronized void ensureStarted() throws Exception {
        if (portOpen()) return;
        String socketName = discoverSocket();
        if (socketName == null) {
            throw new BrowserStateException(
                    "browser_not_ready",
                    "Chrome DevTools socket is not available. Open Chrome once, keep USB debugging enabled, then return to AIHub.");
        }

        File local = extractForwarder();
        String remote = "/data/local/tmp/aihub_cdp_forwarder_" + Process.myUid();
        String log = "/data/local/tmp/aihub_cdp_forwarder_" + Process.myUid() + ".log";
        String command = "cp " + q(local.getAbsolutePath()) + " " + q(remote)
                + " && chmod 755 " + q(remote)
                + " && (" + q(remote) + " " + PORT + " " + q(socketName)
                + " >" + q(log) + " 2>&1 </dev/null &)";
        runSu(command, 10);

        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (portOpen()) return;
            Thread.sleep(100);
        }
        throw new BrowserStateException(
                "root_bridge_failed",
                "Root CDP bridge could not connect to Chrome. Check KernelSU root permission for AIHub.");
    }

    private String discoverSocket() throws Exception {
        String output = runSu("cat /proc/net/unix | grep devtools_remote || true", 5);
        List<String> candidates = new ArrayList<>();
        for (String line : output.split("\\R")) {
            int at = line.lastIndexOf('@');
            if (at < 0) continue;
            String name = line.substring(at + 1).trim();
            if (!name.isEmpty()) candidates.add(name);
        }
        for (String candidate : candidates) {
            if (candidate.equals("chrome_devtools_remote")) return candidate;
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private File extractForwarder() throws Exception {
        File dir = new File(context.getFilesDir(), "native");
        if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Cannot create native directory");
        File output = new File(dir, "aihub_cdp_forwarder");
        try (InputStream in = context.getAssets().open(ASSET)) {
            Files.copy(in, output.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.FileNotFoundException missing) {
            throw new BrowserStateException(
                    "forwarder_missing",
                    "AIHub CDP forwarder is missing from this build. Use the official AIHub build artifact.");
        }
        return output;
    }

    private boolean portOpen() {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", PORT), 250);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String runSu(String command, int timeoutSeconds) throws Exception {
        ProcessBuilder builder = new ProcessBuilder("su", "-c", command);
        builder.redirectErrorStream(true);
        java.lang.Process process = builder.start();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream input = process.getInputStream()) {
                input.transferTo(out);
            } catch (Exception ignored) {}
        }, "AIHub-su-reader");
        reader.start();
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new BrowserStateException("root_timeout", "Root command timed out");
        }
        reader.join(1000);
        String text = out.toString(StandardCharsets.UTF_8);
        if (process.exitValue() != 0) {
            throw new BrowserStateException(
                    "root_denied",
                    "Root command failed. Grant AIHub root access in KernelSU. " + text.trim());
        }
        return text;
    }

    private static String q(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
