package com.yagay.aihub.core.command;

import java.util.List;

/** One command shape for UI, Intent, Binder, shortcuts and automation tools. */
public record AiCommand(
        AiCommandType type,
        String providerId,
        String text,
        List<String> attachments) {

    public AiCommand {
        if (type == null) throw new IllegalArgumentException("type is required");
        providerId = clean(providerId);
        attachments = List.copyOf(attachments == null ? List.of() : attachments);
    }

    public static AiCommand simple(AiCommandType type) {
        return new AiCommand(type, null, null, List.of());
    }

    public static AiCommand switchTo(String providerId) {
        return new AiCommand(AiCommandType.SWITCH, providerId, null, List.of());
    }

    public static AiCommand send(String text) {
        return new AiCommand(AiCommandType.SEND_TEXT, null, text, List.of());
    }

    public static AiCommand sendTo(String providerId, String text) {
        return new AiCommand(AiCommandType.SEND_TEXT, providerId, text, List.of());
    }

    public static AiCommand attach(List<String> uris) {
        return new AiCommand(AiCommandType.ATTACH, null, null, uris);
    }

    public static AiCommand attachAndSend(List<String> uris, String text) {
        return new AiCommand(AiCommandType.ATTACH_AND_SEND, null, text, uris);
    }

    private static String clean(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
