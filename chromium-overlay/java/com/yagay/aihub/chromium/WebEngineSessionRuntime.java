package com.yagay.aihub.chromium;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.yagay.aihub.core.AiSessionKey;
import com.yagay.aihub.core.ProviderConfig;
import com.yagay.aihub.core.runtime.SessionRuntime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.chromium.webengine.Tab;

/**
 * The entire Chromium-facing session adapter. AIHub core knows nothing about WebEngine APIs.
 * One fixed WebEngine profile/persistence id is used per AI provider.
 */
public final class WebEngineSessionRuntime implements SessionRuntime {
    public interface WebEngineHost {
        ListenableFuture<Tab> openOrRestoreTab(String profileName, String persistenceId, String url);
        void showProfile(String profileName);
        void closeProfile(String profileName);
        ListenableFuture<String> attachFiles(
                String profileName,
                ListenableFuture<Tab> tab,
                List<String> uriStrings);
    }

    private record Holder(String profileName, ProviderConfig provider, ListenableFuture<Tab> tabFuture) {}

    private final WebEngineHost host;
    private final Map<AiSessionKey, Holder> holders = new HashMap<>();

    public WebEngineSessionRuntime(WebEngineHost host) { this.host = host; }

    @Override
    public void open(AiSessionKey key, ProviderConfig provider) {
        String safe = sanitize(provider.id());
        String profileName = "aihub_provider_" + safe;
        String persistenceId = "aihub_session_" + safe;
        ListenableFuture<Tab> future = host.openOrRestoreTab(profileName, persistenceId, provider.homeUrl());
        holders.put(key, new Holder(profileName, provider, future));
    }

    @Override
    public void activate(AiSessionKey key) {
        Holder h = require(key);
        host.showProfile(h.profileName());
        Futures.addCallback(
                h.tabFuture(),
                new com.google.common.util.concurrent.FutureCallback<>() {
                    @Override public void onSuccess(Tab tab) { if (tab != null) tab.setActive(); }
                    @Override public void onFailure(Throwable thrown) {}
                },
                Runnable::run);
    }

    @Override
    public void close(AiSessionKey key) {
        Holder h = holders.remove(key);
        if (h != null) host.closeProfile(h.profileName());
    }

    @Override public void sendText(AiSessionKey key, String text) {
        Holder h = require(key);
        execute(h, GenericDomScriptFactory.fillAndSend(text, h.provider()));
    }

    @Override public void newChat(AiSessionKey key) {
        Holder h = require(key);
        execute(h, GenericDomScriptFactory.newChat(h.provider()));
    }

    @Override public void stop(AiSessionKey key) {
        Holder h = require(key);
        execute(h, GenericDomScriptFactory.stop(h.provider()));
    }

    @Override
    public void attach(AiSessionKey key, List<String> uriStrings) {
        Holder h = require(key);
        if (uriStrings == null || uriStrings.isEmpty()) {
            execute(h, GenericDomScriptFactory.findAttachmentControl());
            return;
        }
        host.attachFiles(h.profileName(), h.tabFuture(), List.copyOf(uriStrings));
    }

    @Override
    public void attachAndSend(AiSessionKey key, List<String> uriStrings, String text) {
        Holder h = require(key);
        List<String> files = List.copyOf(uriStrings == null ? List.of() : uriStrings);
        if (files.isEmpty()) {
            sendText(key, text);
            return;
        }
        if (text == null || text.isBlank()) {
            attach(key, files);
            return;
        }
        Futures.transformAsync(
                host.attachFiles(h.profileName(), h.tabFuture(), files),
                ignored -> Futures.transformAsync(
                        h.tabFuture(),
                        tab -> tab.executeScript(GenericDomScriptFactory.fillAndSend(text, h.provider()), false),
                        Runnable::run),
                Runnable::run);
    }

    @Override public void back(AiSessionKey key) {
        withTab(require(key), tab -> tab.getNavigationController().goBack());
    }
    @Override public void forward(AiSessionKey key) {
        withTab(require(key), tab -> tab.getNavigationController().goForward());
    }
    @Override public void reload(AiSessionKey key) {
        withTab(require(key), tab -> tab.getNavigationController().reload());
    }

    public ListenableFuture<String> probe(AiSessionKey key) {
        Holder h = require(key);
        return Futures.transformAsync(
                h.tabFuture(),
                tab -> tab.executeScript(GenericDomScriptFactory.probe(h.provider()), false),
                Runnable::run);
    }

    public ListenableFuture<String> currentUrl(AiSessionKey key) {
        Holder h = require(key);
        return Futures.transform(
                h.tabFuture(),
                tab -> tab.getDisplayUri() == null ? "" : tab.getDisplayUri().toString(),
                Runnable::run);
    }

    private static void execute(Holder holder, String script) {
        Futures.transformAsync(holder.tabFuture(), tab -> tab.executeScript(script, false), Runnable::run);
    }

    private static void withTab(Holder holder, java.util.function.Consumer<Tab> action) {
        Futures.addCallback(
                holder.tabFuture(),
                new com.google.common.util.concurrent.FutureCallback<>() {
                    @Override public void onSuccess(Tab tab) { if (tab != null) action.accept(tab); }
                    @Override public void onFailure(Throwable thrown) {}
                },
                Runnable::run);
    }

    private Holder require(AiSessionKey key) {
        Holder h = holders.get(key);
        if (h == null) throw new IllegalStateException("Session not opened: " + key);
        return h;
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
