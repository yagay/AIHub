package com.yagay.aihub.model;

/** One normalized conversation item mirrored from an AI website. */
public record ChatMessage(String role, String text) {
    public ChatMessage {
        role = role == null || role.isBlank() ? "assistant" : role;
        text = text == null ? "" : text.trim();
    }
}
