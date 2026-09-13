package com.yagay.aihub.session;

/** A browser session is uniquely identified by AI provider + account. */
public record SessionKey(String providerId, String accountId) {}
