package com.yagay.aihub.core.runtime;

import com.yagay.aihub.core.AiAccount;
import com.yagay.aihub.core.AiSessionKey;
import com.yagay.aihub.core.ProviderConfig;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Small deterministic runtime used by unit/smoke tests. */
public final class RecordingSessionRuntime implements SessionRuntime {
    private final List<String> events = new ArrayList<>();

    public List<String> events() { return Collections.unmodifiableList(events); }

    @Override public void open(AiSessionKey key, ProviderConfig provider, AiAccount account) {
        events.add("open:" + key + ":profile=" + account.profileName());
    }
    @Override public void activate(AiSessionKey key) { events.add("activate:" + key); }
    @Override public void close(AiSessionKey key) { events.add("close:" + key); }
    @Override public void sendText(AiSessionKey key, String text) { events.add("send:" + key + ":" + text); }
    @Override public void newChat(AiSessionKey key) { events.add("newChat:" + key); }
    @Override public void stop(AiSessionKey key) { events.add("stop:" + key); }
    @Override public void attach(AiSessionKey key, List<String> uriStrings) { events.add("attach:" + key + ":" + uriStrings.size()); }
    @Override public void attachAndSend(AiSessionKey key, List<String> uriStrings, String text) {
        events.add("attachAndSend:" + key + ":" + uriStrings.size() + ":" + text);
    }
    @Override public void back(AiSessionKey key) { events.add("back:" + key); }
    @Override public void forward(AiSessionKey key) { events.add("forward:" + key); }
    @Override public void reload(AiSessionKey key) { events.add("reload:" + key); }
}
