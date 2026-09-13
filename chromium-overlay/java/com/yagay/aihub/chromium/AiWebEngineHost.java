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

/**
 * The single direct Chromium/WebEngine adaptation point.
 *
 * Keep upstream API churn here. AIHub deliberately asks TabManager for the current active tab at
 * operation time instead of caching the first tab forever, so OAuth/login/new-window flows can
 * change Chromium's active tab without disconnecting AIHub actions from the visible page.
 */
public final class AiWebEngineHost implements WebEngineSessionRuntime.WebEngineHost {
    private static final int UPLOAD_CHUNK_CHARS = 160_000;
    private static final long MAX_UPLOAD_BYTES = 64L * 1024L * 1024L;

    private record UploadFile(String name, String mime, String base64) {}

    private final Context context;
    private final FragmentManager fragmentManager;
    private final int containerViewId;
    private final ListenableFuture<WebSandbox> sandboxFuture;
    private final Map<String, WebFragment> fragments = new HashMap<>();
    private final Map<String, ListenableFuture<TabManager>> managers = new HashMap<>();
    private final Map<String, String> homeUrls = new HashMap<>();
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
    public void openOrRestoreSession(String profileName, String persistenceId, String homeUrl) {
        requireMainThread();
        if (managers.containsKey(profileName)) return;
        homeUrls.put(profileName, homeUrl);

        ListenableFuture<TabManager> managerFuture = Futures.transformAsync(
                sandboxFuture,
                sandbox -> {
                    WebFragment fragment = getOrCreateFragment(sandbox, profileName, persistenceId);
                    return fragment.getTabManager();
                },
                context.getMainExecutor());

        // Store the manager future immediately so showProfile() and other operations can safely queue
        // behind WebEngine initialization.
        managers.put(profileName, managerFuture);

        Futures.addCallback(
                currentActiveTab(profileName),
                new FutureCallback<>() {
                    @Override public void onSuccess(Tab tab) {
                        if (tab != null) tab.setActive();
                    }
                    @Override public void onFailure(Throwable error) {
                        showBrowserError("Could not open AI session", error);
                    }
                },
                context.getMainExecutor());
    }

    @Override
    public void showProfile(String profileName) {
        requireMainThread();
        visibleProfile = profileName;
        WebFragment target = fragments.get(profileName);
        if (target == null || fragmentManager.isStateSaved()) return;

        var tx = fragmentManager.beginTransaction().setReorderingAllowed(true);
        for (Map.Entry<String, WebFragment> entry : fragments.entrySet()) {
            if (entry.getKey().equals(profileName)) tx.show(entry.getValue());
            else tx.hide(entry.getValue());
        }
        tx.commitNow();

        // Do not force an old cached tab active. Resolve whatever Chromium currently considers active.
        Futures.addCallback(
                currentActiveTab(profileName),
                new FutureCallback<>() {
                    @Override public void onSuccess(Tab tab) {
                        if (tab != null) tab.setActive();
                    }
                    @Override public void onFailure(Throwable ignored) {}
                },
                context.getMainExecutor());
    }

    @Override
    public void closeProfile(String profileName) {
        requireMainThread();
        WebFragment fragment = fragments.remove(profileName);
        managers.remove(profileName);
        homeUrls.remove(profileName);
        if (fragment != null && !fragmentManager.isStateSaved()) {
            fragmentManager.beginTransaction().remove(fragment).commitNow();
        }
        if (profileName.equals(visibleProfile)) visibleProfile = null;
    }

    @Override
    public ListenableFuture<String> executeScript(String profileName, String script) {
        return Futures.transformAsync(
                currentActiveTab(profileName),
                tab -> tab.executeScript(script, false),
                context.getMainExecutor());
    }

    @Override
    public ListenableFuture<String> attachFiles(String profileName, List<String> uriStrings) {
        if (uriStrings == null || uriStrings.isEmpty()) {
            return Futures.immediateFuture("{\"ok\":true,\"count\":0}");
        }

        ListenableFuture<List<UploadFile>> readFuture = uploadExecutor.submit(() -> readFiles(uriStrings));
        ListenableFuture<String> result = Futures.transformAsync(
                readFuture,
                files -> Futures.transformAsync(
                        currentActiveTab(profileName),
                        // Capture one active tab for the complete upload transaction. If Chromium
                        // changes tabs midway through an upload, we must not split one file across tabs.
                        tab -> injectFiles(tab, files),
                        context.getMainExecutor()),
                context.getMainExecutor());

        Futures.addCallback(
                result,
                new FutureCallback<>() {
                    @Override public void onSuccess(String ignored) {}
                    @Override public void onFailure(Throwable error) {
                        showBrowserError("Attachment failed", error);
                    }
                },
                context.getMainExecutor());
        return result;
    }

    @Override
    public void back(String profileName) {
        withActiveTab(profileName, tab -> tab.getNavigationController().goBack());
    }

    @Override
    public void forward(String profileName) {
        withActiveTab(profileName, tab -> tab.getNavigationController().goForward());
    }

    @Override
    public void reload(String profileName) {
        withActiveTab(profileName, tab -> tab.getNavigationController().reload());
    }

    @Override
    public ListenableFuture<String> currentUrl(String profileName) {
        return Futures.transform(
                currentActiveTab(profileName),
                tab -> tab.getDisplayUri() == null ? "" : tab.getDisplayUri().toString(),
                context.getMainExecutor());
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
                    if (total > MAX_UPLOAD_BYTES) {
                        throw new IllegalArgumentException("Attachments exceed 64 MiB limit");
                    }
                    buffer.write(block, 0, read);
                }
                bytes = buffer.toByteArray();
            }
            out.add(new UploadFile(name, mime, Base64.encodeToString(bytes, Base64.NO_WRAP)));
        }
        return out;
    }

    private ListenableFuture<String> injectFiles(Tab tab, List<UploadFile> files) {
        ListenableFuture<String> chain = tab.executeScript(
                GenericDomScriptFactory.uploadInit(files.size()), false);

        for (int fileIndex = 0; fileIndex < files.size(); fileIndex++) {
            UploadFile file = files.get(fileIndex);
            for (int start = 0; start < file.base64().length(); start += UPLOAD_CHUNK_CHARS) {
                int index = fileIndex;
                String chunk = file.base64().substring(
                        start,
                        Math.min(file.base64().length(), start + UPLOAD_CHUNK_CHARS));
                chain = Futures.transformAsync(
                        chain,
                        ignored -> tab.executeScript(
                                GenericDomScriptFactory.uploadAppend(
                                        index, file.name(), file.mime(), chunk),
                                false),
                        context.getMainExecutor());
            }
        }

        return Futures.transformAsync(
                chain,
                ignored -> Futures.transform(
                        tab.executeScript(GenericDomScriptFactory.uploadCommit(), false),
                        AiWebEngineHost::requireSuccessfulUpload,
                        context.getMainExecutor()),
                context.getMainExecutor());
    }

    private ListenableFuture<Tab> currentActiveTab(String profileName) {
        ListenableFuture<TabManager> managerFuture = requireManager(profileName);
        return Futures.transformAsync(
                managerFuture,
                manager -> Futures.transformAsync(
                        manager.getActiveTab(),
                        active -> {
                            if (active != null) {
                                navigateIfEmpty(active, homeUrls.get(profileName));
                                return Futures.immediateFuture(active);
                            }
                            return Futures.transform(
                                    manager.createTab(),
                                    tab -> {
                                        navigateIfEmpty(tab, homeUrls.get(profileName));
                                        if (tab != null) tab.setActive();
                                        return tab;
                                    },
                                    context.getMainExecutor());
                        },
                        context.getMainExecutor()),
                context.getMainExecutor());
    }

    private void withActiveTab(String profileName, java.util.function.Consumer<Tab> action) {
        Futures.addCallback(
                currentActiveTab(profileName),
                new FutureCallback<>() {
                    @Override public void onSuccess(Tab tab) {
                        if (tab != null) action.accept(tab);
                    }
                    @Override public void onFailure(Throwable error) {
                        showBrowserError("Browser action failed", error);
                    }
                },
                context.getMainExecutor());
    }

    private ListenableFuture<TabManager> requireManager(String profileName) {
        ListenableFuture<TabManager> manager = managers.get(profileName);
        if (manager == null) throw new IllegalStateException("Browser session not opened: " + profileName);
        return manager;
    }

    private static String requireSuccessfulUpload(String result) {
        String value = result == null ? "" : result;
        if (value.contains("\"ok\":true") || value.contains("\\\"ok\\\":true")) return value;
        throw new IllegalStateException("Page rejected attachment: " + value);
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            try (Cursor cursor = resolver.query(
                    uri,
                    new String[]{OpenableColumns.DISPLAY_NAME},
                    null,
                    null,
                    null)) {
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
        if (restored instanceof WebFragment) {
            fragment = (WebFragment) restored;
        } else {
            FragmentParams params = new FragmentParams.Builder()
                    .setProfileName(profileName)
                    .setPersistenceId(persistenceId)
                    .build();
            fragment = sandbox.createFragment(params);
            fragmentManager.beginTransaction()
                    .setReorderingAllowed(true)
                    .add(containerViewId, fragment, tag)
                    .hide(fragment)
                    .commitNow();
        }

        fragments.put(profileName, fragment);
        if (profileName.equals(visibleProfile)) showProfile(profileName);
        return fragment;
    }

    private static void navigateIfEmpty(Tab tab, String url) {
        if (tab == null || url == null || url.isBlank()) return;
        if (tab.getDisplayUri() == null || Uri.EMPTY.equals(tab.getDisplayUri())) {
            tab.getNavigationController().navigate(url);
        }
    }

    private void showBrowserError(String prefix, Throwable error) {
        String detail = error == null || error.getMessage() == null
                ? (error == null ? "Unknown error" : error.getClass().getSimpleName())
                : error.getMessage();
        Toast.makeText(context, prefix + ": " + detail, Toast.LENGTH_LONG).show();
    }

    private static String tag(String profileName) {
        return "AIHUB_PROFILE_" + profileName;
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("AI Hub WebEngine host must run on Android main thread");
        }
    }
}
