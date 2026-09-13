package com.yagay.aihub.core.command;

import java.util.List;

/** One command shape for UI, Intent, Binder, shortcuts and automation tools. */
public record AiCommand(
        AiCommandType type,
        String providerId,
        String accountId,
        String workspaceId,
        String text,
        List<String> attachments) {

    public AiCommand {
        if (type == null) throw new IllegalArgumentException("type is required");
        attachments = List.copyOf(attachments == null ? List.of() : attachments);
    }

    public static AiCommand simple(AiCommandType type) {
        return new AiCommand(type, null, null, null, null, List.of());
    }

    public static AiCommand switchTo(String provider, String account, String workspace) {
        return new AiCommand(AiCommandType.SWITCH, provider, account, workspace, null, List.of());
    }

    public static AiCommand send(String text) {
        return new AiCommand(AiCommandType.SEND_TEXT, null, null, null, text, List.of());
    }

    public static AiCommand sendTo(String provider, String account, String workspace, String text) {
        return new AiCommand(AiCommandType.SEND_TEXT, provider, account, workspace, text, List.of());
    }

    public static AiCommand attach(List<String> uris) {
        return new AiCommand(AiCommandType.ATTACH, null, null, null, null, uris);
    }

    public static AiCommand attachAndSend(List<String> uris, String text) {
        return new AiCommand(AiCommandType.ATTACH_AND_SEND, null, null, null, text, uris);
    }
}
