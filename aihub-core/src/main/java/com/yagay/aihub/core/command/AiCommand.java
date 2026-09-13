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
        attachments = List.copyOf(attachments == null ? List.of() : attachments);
    }

    public static AiCommand switchTo(String provider, String account, String workspace) {
        return new AiCommand(AiCommandType.SWITCH, provider, account, workspace, null, List.of());
    }

    public static AiCommand send(String text) {
        return new AiCommand(AiCommandType.SEND_TEXT, null, null, null, text, List.of());
    }
}
