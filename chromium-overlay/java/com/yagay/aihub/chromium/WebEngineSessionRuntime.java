package com.yagay.aihub.chromium;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.yagay.aihub.core.AiSessionKey;
import com.yagay.aihub.core.ProviderConfig;
import com.yagay.aihub.core.runtime.SessionRuntime;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Stable AIHub-to-browser bridge.
 *
 * This class intentionally contains no org.chromium.webengine imports. All upstream Chromium API
 * churn is isolated behind WebEngineHost (implemented by AiWebEngineHost).
 */
public final class WebEngineSessionRuntime implements SessionRuntime {
    public interface WebEngineHost {
        void openOrRestoreSession(String profileName, String persistenceId, String homeUrl);
        void showProfile(String profileName);
        void closeProfile(String profileName);
        ListenableFuture<String> executeScript(String profileName, String script);
        ListenableFuture<String> attachFiles(String profileName, List<String> uriStrings);
        void back(String profileName);
        void forward(String profileName);
        void reload(String profileName);
        ListenableFuture<String> currentUrl(String profileName);
    }

    private record Holder(String profileName, ProviderConfig provider) {}

    private final WebEngineHost host;
    private final Map<AiSessionKey, Holder> holders = new HashMap<>();

    public WebEngineSessionRuntime(WebEngineHost host) {
        this.host = host;
    }

    @Override
    public void open(AiSessionKey key, ProviderConfig provider) {
        String safe = sanitize(provider.id());
        String profileName = "aihub_provider_" + safe;
        String persistenceId = "aihub_session_" + safe;
        host.openOrRestoreSession(profileName, persistenceId, provider.homeUrl());
        holders.put(key, new Holder(profileName, provider));
    }

    @Override
    public void activate(AiSessionKey key) {
        host.showProfile(require(key).profileName());
    }

    @Override
    public void close(AiSessionKey key) {
        Holder holder = holders.remove(key);
        if (holder != null) host.closeProfile(holder.profileName());
    }

    @Override
    public void sendText(AiSessionKey key, String text) {
        Holder holder = require(key);
        host.executeScript(
                holder.profileName(),
                GenericDomScriptFactory.fillAndSend(text, holder.provider()));
    }

    @Override
    public void newChat(AiSessionKey key) {
        Holder holder = require(key);
        host.executeScript(
                holder.profileName(),
                GenericDomScriptFactory.newChat(holder.provider()));
    }

    @Override
    public void stop(AiSessionKey key) {
        Holder holder = require(key);
        host.executeScript(
                holder.profileName(),
                GenericDomScriptFactory.stop(holder.provider()));
    }

    @Override
    public void attach(AiSessionKey key, List<String> uriStrings) {
        Holder holder = require(key);
        List<String> files = List.copyOf(uriStrings == null ? List.of() : uriStrings);
        if (files.isEmpty()) {
            host.executeScript(holder.profileName(), GenericDomScriptFactory.findAttachmentControl());
            return;
        }
        host.attachFiles(holder.profileName(), files);
    }

    @Override
    public void attachAndSend(AiSessionKey key, List<String> uriStrings, String text) {
        Holder holder = require(key);
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
                host.attachFiles(holder.profileName(), files),
                ignored -> host.executeScript(
                        holder.profileName(),
                        GenericDomScriptFactory.fillAndSend(text, holder.provider())),
                Runnable::run);
    }

    @Override
    public void back(AiSessionKey key) {
        host.back(require(key).profileName());
    }

    @Override
    public void forward(AiSessionKey key) {
        host.forward(require(key).profileName());
    }

    @Override
    public void reload(AiSessionKey key) {
        host.reload(require(key).profileName());
    }

    public ListenableFuture<String> probe(AiSessionKey key) {
        Holder holder = require(key);
        return host.executeScript(
                holder.profileName(),
                GenericDomScriptFactory.probe(holder.provider()));
    }

    public ListenableFuture<String> currentUrl(AiSessionKey key) {
        return host.currentUrl(require(key).profileName());
    }

    private Holder require(AiSessionKey key) {
        Holder holder = holders.get(key);
        if (holder == null) throw new IllegalStateException("Session not opened: " + key);
        return holder;
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9_]", "_");
    }
}
