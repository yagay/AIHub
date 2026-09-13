package com.yagay.aihub.core;

import java.time.Instant;
import java.util.Objects;

public final class AiSession {
    private final AiSessionKey key;
    private String url;
    private String title;
    private SessionState state;
    private Instant lastActivatedAt;

    public AiSession(AiSessionKey key, String url) {
        this.key = Objects.requireNonNull(key);
        this.url = Objects.requireNonNull(url);
        this.state = SessionState.CREATED;
        this.lastActivatedAt = Instant.now();
    }

    public AiSessionKey key() { return key; }
    public String url() { return url; }
    public String title() { return title; }
    public SessionState state() { return state; }
    public Instant lastActivatedAt() { return lastActivatedAt; }

    public void setUrl(String url) { this.url = Objects.requireNonNull(url); }
    public void setTitle(String title) { this.title = title; }
    public void setState(SessionState state) { this.state = Objects.requireNonNull(state); }
    public void markActivated() { this.lastActivatedAt = Instant.now(); }
}
