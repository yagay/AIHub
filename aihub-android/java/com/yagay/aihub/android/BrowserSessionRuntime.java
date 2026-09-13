package com.yagay.aihub.android;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Base64;

import androidx.annotation.Nullable;

import com.yagay.aihub.core.AiSessionKey;
import com.yagay.aihub.core.ProviderConfig;
import com.yagay.aihub.core.runtime.SessionRuntime;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Stable Android implementation of {@link SessionRuntime}.
 *
 * This class knows about Android and AIHub provider rules, but deliberately knows nothing about
 * Chrome internals. The fork-specific adapter only implements {@link AiHubBrowserHost}.
 */
public final class BrowserSessionRuntime implements SessionRuntime {
    private static final int UPLOAD_CHUNK_CHARS = 160_000;
    private static final long MAX_UPLOAD_BYTES = 64L * 1024L * 1024L;

    private record UploadFile(String name, String mime, String base64) {}

    private final AiHubBrowserHost host;
    private final Map<AiSessionKey, ProviderConfig> providers = new HashMap<>();
    private final ExecutorService fileExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "AIHubFileBridge");
        thread.setDaemon(true);
        return thread;
    });

    public BrowserSessionRuntime(AiHubBrowserHost host) {
        this.host = host;
    }

    @Override
    public void open(AiSessionKey key, ProviderConfig provider) {
        providers.put(key, provider);
        host.openOrSelectProvider(provider.id(), provider.homeUrl());
    }

    @Override
    public void activate(AiSessionKey key) {
        host.selectProvider(provider(key).id());
    }

    @Override
    public void close(AiSessionKey key) {
        ProviderConfig provider = providers.remove(key);
        if (provider != null) host.closeProvider(provider.id());
    }

    @Override
    public void sendText(AiSessionKey key, String text) {
        host.evaluateJavaScript(GenericDomScriptFactory.fillAndSend(text, provider(key)), null);
    }

    @Override
    public void newChat(AiSessionKey key) {
        host.evaluateJavaScript(GenericDomScriptFactory.newChat(provider(key)), null);
    }

    @Override
    public void stop(AiSessionKey key) {
        host.evaluateJavaScript(GenericDomScriptFactory.stop(provider(key)), null);
    }

    @Override
    public void attach(AiSessionKey key, List<String> uriStrings) {
        List<String> uris = uriStrings == null ? List.of() : List.copyOf(uriStrings);
        if (uris.isEmpty()) {
            // Normal in-app attachment path: click the real website file input and let Chrome own
            // its native file chooser, permissions and URI lifecycle.
            host.evaluateJavaScript(GenericDomScriptFactory.openAttachmentChooser(), null);
            return;
        }
        injectSharedFiles(uris, null);
    }

    @Override
    public void attachAndSend(AiSessionKey key, List<String> uriStrings, String text) {
        List<String> uris = uriStrings == null ? List.of() : List.copyOf(uriStrings);
        if (uris.isEmpty()) {
            sendText(key, text);
            return;
        }
        injectSharedFiles(uris, () -> {
            if (text != null && !text.isBlank()) sendText(key, text);
        });
    }

    @Override public void back(AiSessionKey key) { host.back(); }
    @Override public void forward(AiSessionKey key) { host.forward(); }
    @Override public void reload(AiSessionKey key) { host.reload(); }

    public void probe(AiSessionKey key, @Nullable android.webkit.ValueCallback<String> callback) {
        host.evaluateJavaScript(GenericDomScriptFactory.probe(provider(key)), callback);
    }

    public String currentUrl() { return host.currentUrl(); }
    public boolean isLoading() { return host.isLoading(); }
    public int loadProgress() { return host.loadProgress(); }

    public void shutdown() {
        fileExecutor.shutdownNow();
    }

    private void injectSharedFiles(List<String> uriStrings, @Nullable Runnable success) {
        fileExecutor.execute(() -> {
            try {
                List<UploadFile> files = readFiles(uriStrings);
                host.activity().runOnUiThread(() -> beginUpload(files, success));
            } catch (Exception error) {
                host.activity().runOnUiThread(() -> host.onAiHubError(
                        "Attachment failed: " + message(error)));
            }
        });
    }

    private void beginUpload(List<UploadFile> files, @Nullable Runnable success) {
        host.evaluateJavaScript(GenericDomScriptFactory.uploadInit(files.size()), ignored ->
                appendFileChunk(files, 0, 0, success));
    }

    private void appendFileChunk(
            List<UploadFile> files, int fileIndex, int offset, @Nullable Runnable success) {
        if (fileIndex >= files.size()) {
            host.evaluateJavaScript(GenericDomScriptFactory.uploadCommit(), result -> {
                String value = result == null ? "" : result;
                if (value.contains("\\\"ok\\\":true") || value.contains("\"ok\":true")) {
                    if (success != null) success.run();
                } else {
                    host.onAiHubError("Website rejected attachment: " + value);
                }
            });
            return;
        }

        UploadFile file = files.get(fileIndex);
        if (offset >= file.base64().length()) {
            appendFileChunk(files, fileIndex + 1, 0, success);
            return;
        }

        int end = Math.min(file.base64().length(), offset + UPLOAD_CHUNK_CHARS);
        String chunk = file.base64().substring(offset, end);
        host.evaluateJavaScript(
                GenericDomScriptFactory.uploadAppend(
                        fileIndex, file.name(), file.mime(), chunk),
                ignored -> appendFileChunk(files, fileIndex, end, success));
    }

    private List<UploadFile> readFiles(List<String> uriStrings) throws Exception {
        ContentResolver resolver = host.activity().getContentResolver();
        List<UploadFile> out = new ArrayList<>();
        long total = 0;
        for (String raw : uriStrings) {
            Uri uri = Uri.parse(raw);
            String name = displayName(resolver, uri);
            String mime = resolver.getType(uri);
            if (mime == null || mime.isBlank()) mime = "application/octet-stream";

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (InputStream input = resolver.openInputStream(uri)) {
                if (input == null) throw new IllegalArgumentException("Cannot open " + uri);
                byte[] block = new byte[64 * 1024];
                int read;
                while ((read = input.read(block)) != -1) {
                    total += read;
                    if (total > MAX_UPLOAD_BYTES) {
                        throw new IllegalArgumentException("Attachments exceed 64 MiB limit");
                    }
                    buffer.write(block, 0, read);
                }
            }
            out.add(new UploadFile(
                    name,
                    mime,
                    Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP)));
        }
        return out;
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            try (Cursor cursor = resolver.query(
                    uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        String value = cursor.getString(index);
                        if (value != null && !value.isBlank()) return value;
                    }
                }
            } catch (RuntimeException ignored) {}
        }
        String last = uri.getLastPathSegment();
        return last == null || last.isBlank() ? "upload.bin" : last;
    }

    private ProviderConfig provider(AiSessionKey key) {
        ProviderConfig provider = providers.get(key);
        if (provider == null) throw new IllegalStateException("Session not opened: " + key);
        return provider;
    }

    private static String message(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }
}
