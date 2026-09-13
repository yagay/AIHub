package com.yagay.aihub.chromium;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Base64;
import android.widget.Toast;

import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import org.chromium.webengine.FragmentParams;
import org.chromium.webengine.Tab;
import org.chromium.webengine.TabManager;
import org.chromium.webengine.WebFragment;
import org.chromium.webengine.WebSandbox;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/** Keeps Chromium/WebEngine API churn and Android file bridging in one class. */
public final class AiWebEngineHost implements WebEngineSessionRuntime.WebEngineHost {
    private static final int UPLOAD_CHUNK_CHARS = 160_000;
    private static final long MAX_UPLOAD_BYTES = 64L * 1024L * 1024L;

    private record UploadFile(String name, String mime, String base64) {}

    private final Context context;
    private final FragmentManager fragmentManager;
    private final int containerViewId;
    private final ListenableFuture<WebSandbox> sandboxFuture;
    private final Map<String, WebFragment> fragments = new HashMap<>();
    private final Map<String, ListenableFuture<Tab>> tabs = new HashMap<>();
    private final ListeningExecutorService uploadExecutor = MoreExecutors.listeningDecorator(
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "AIHubUpload");
                thread.setDaemon(true);
                return thread;
            }));
    private String visibleProfile;

    public AiWebEngineHost(Context context, FragmentManager fragmentManager, int containerViewId) {
        this.context = context.getApplicationContext();
        this.fragmentManager = fragmentManager;
        this.containerViewId = containerViewId;
        this.sandboxFuture = WebSandbox.create(this.context);
    }

    @Override
    public ListenableFuture<Tab> openOrRestoreTab(String profileName, String persistenceId, String url) {
        requireMainThread();
        ListenableFuture<Tab> existing = tabs.get(profileName);
        if (existing != null) return existing;
        ListenableFuture<Tab> created = Futures.transformAsync(sandboxFuture, sandbox -> {
            WebFragment fragment = getOrCreateFragment(sandbox, profileName, persistenceId);
            return Futures.transformAsync(fragment.getTabManager(),
                    manager -> getOrCreateActiveTab(manager, url), context.getMainExecutor());
        }, context.getMainExecutor());
        tabs.put(profileName, created);
        return created;
    }

    @Override
    public void showProfile(String profileName) {
        requireMainThread();
        WebFragment target = fragments.get(profileName);
        if (target == null) { visibleProfile = profileName; return; }
        if (fragmentManager.isStateSaved()) return;
        var tx = fragmentManager.beginTransaction().setReorderingAllowed(true);
        for (Map.Entry<String, WebFragment> entry : fragments.entrySet()) {
            if (entry.getKey().equals(profileName)) tx.show(entry.getValue()); else tx.hide(entry.getValue());
        }
        tx.commitNow();
        visibleProfile = profileName;
    }

    @Override
    public void closeProfile(String profileName) {
        requireMainThread();
        WebFragment fragment = fragments.remove(profileName);
        tabs.remove(profileName);
        if (fragment != null && !fragmentManager.isStateSaved()) {
            fragmentManager.beginTransaction().remove(fragment).commitNow();
        }
        if (profileName.equals(visibleProfile)) visibleProfile = null;
    }

    @Override
    public ListenableFuture<String> attachFiles(
            String profileName,
            ListenableFuture<Tab> tabFuture,
            List<String> uriStrings) {
        if (uriStrings == null || uriStrings.isEmpty()) {
            return Futures.immediateFuture("{\"ok\":true,\"count\":0}");
        }
        ListenableFuture<List<UploadFile>> readFuture = uploadExecutor.submit(() -> readFiles(uriStrings));
        ListenableFuture<String> result = Futures.transformAsync(
                readFuture,
                files -> injectFiles(tabFuture, files),
                context.getMainExecutor());
        Futures.addCallback(result, new FutureCallback<>() {
            @Override public void onSuccess(String ignored) {}
            @Override public void onFailure(Throwable error) {
                Toast.makeText(context,
                        "Attachment failed: " + (error.getMessage() == null
                                ? error.getClass().getSimpleName() : error.getMessage()),
                        Toast.LENGTH_LONG).show();
            }
        }, context.getMainExecutor());
        return result;
    }

    private List<UploadFile> readFiles(List<String> uriStrings) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        List<UploadFile> out = new ArrayList<>();
        long total = 0;
        for (String raw : uriStrings) {
            Uri uri = Uri.parse(raw);
            String name = displayName(resolver, uri);
            String mime = resolver.getType(uri);
            if (mime == null || mime.isBlank()) mime = "application/octet-stream";
            byte[] bytes;
            try (InputStream input = resolver.openInputStream(uri)) {
                if (input == null) throw new IllegalArgumentException("Cannot open " + uri);
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] block = new byte[64 * 1024];
                int read;
                while ((read = input.read(block)) != -1) {
                    total += read;
                    if (total > MAX_UPLOAD_BYTES) throw new IllegalArgumentException("Attachments exceed 64 MiB limit");
                    buffer.write(block, 0, read);
                }
                bytes = buffer.toByteArray();
            }
            out.add(new UploadFile(name, mime, Base64.encodeToString(bytes, Base64.NO_WRAP)));
        }
        return out;
    }

    private ListenableFuture<String> injectFiles(ListenableFuture<Tab> tabFuture, List<UploadFile> files) {
        ListenableFuture<String> chain = Futures.transformAsync(tabFuture,
                tab -> tab.executeScript(GenericDomScriptFactory.uploadInit(files.size()), false),
                context.getMainExecutor());
        for (int fileIndex = 0; fileIndex < files.size(); fileIndex++) {
            UploadFile file = files.get(fileIndex);
            for (int start = 0; start < file.base64().length(); start += UPLOAD_CHUNK_CHARS) {
                int index = fileIndex;
                String chunk = file.base64().substring(start,
                        Math.min(file.base64().length(), start + UPLOAD_CHUNK_CHARS));
                chain = Futures.transformAsync(chain, ignored -> Futures.transformAsync(tabFuture,
                        tab -> tab.executeScript(GenericDomScriptFactory.uploadAppend(
                                index, file.name(), file.mime(), chunk), false),
                        context.getMainExecutor()), context.getMainExecutor());
            }
        }
        return Futures.transformAsync(chain, ignored -> Futures.transformAsync(tabFuture,
                tab -> Futures.transform(
                        tab.executeScript(GenericDomScriptFactory.uploadCommit(), false),
                        AiWebEngineHost::requireSuccessfulUpload,
                        context.getMainExecutor()),
                context.getMainExecutor()), context.getMainExecutor());
    }

    private static String requireSuccessfulUpload(String result) {
        String value = result == null ? "" : result;
        if (value.contains("\"ok\":true") || value.contains("\\\"ok\\\":true")) return value;
        throw new IllegalStateException("Page rejected attachment: " + value);
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (index >= 0) {
                        String name = cursor.getString(index);
                        if (name != null && !name.isBlank()) return name;
                    }
                }
            } catch (RuntimeException ignored) {}
        }
        String last = uri.getLastPathSegment();
        return last == null || last.isBlank() ? "upload.bin" : last;
    }

    private WebFragment getOrCreateFragment(WebSandbox sandbox, String profileName, String persistenceId) {
        WebFragment existing = fragments.get(profileName);
        if (existing != null) return existing;
        String tag = tag(profileName);
        Fragment restored = fragmentManager.findFragmentByTag(tag);
        WebFragment fragment;
        if (restored instanceof WebFragment) fragment = (WebFragment) restored;
        else {
            FragmentParams params = new FragmentParams.Builder().setProfileName(profileName)
                    .setPersistenceId(persistenceId).build();
            fragment = sandbox.createFragment(params);
            fragmentManager.beginTransaction().setReorderingAllowed(true)
                    .add(containerViewId, fragment, tag).hide(fragment).commitNow();
        }
        fragments.put(profileName, fragment);
        if (profileName.equals(visibleProfile)) showProfile(profileName);
        return fragment;
    }

    private ListenableFuture<Tab> getOrCreateActiveTab(TabManager manager, String url) {
        return Futures.transformAsync(manager.getActiveTab(), active -> {
            if (active != null) { navigateIfEmpty(active, url); return Futures.immediateFuture(active); }
            return Futures.transform(manager.createTab(), tab -> { navigateIfEmpty(tab, url); return tab; },
                    context.getMainExecutor());
        }, context.getMainExecutor());
    }

    private static void navigateIfEmpty(Tab tab, String url) {
        if (tab != null && (tab.getDisplayUri() == null || Uri.EMPTY.equals(tab.getDisplayUri()))) {
            tab.getNavigationController().navigate(url);
        }
    }

    private static String tag(String profileName) { return "AIHUB_PROFILE_" + profileName; }
    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("AI Hub WebEngine host must run on Android main thread");
        }
    }
}
