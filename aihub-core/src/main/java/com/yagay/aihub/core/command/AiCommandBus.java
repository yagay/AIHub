package com.yagay.aihub.core.command;

import com.yagay.aihub.core.SessionManager;
import java.util.Objects;

public final class AiCommandBus {
    private final SessionManager sessions;

    public AiCommandBus(SessionManager sessions) {
        this.sessions = Objects.requireNonNull(sessions);
    }

    public synchronized CommandResult execute(AiCommand command) {
        if (command == null) return CommandResult.error("command is null");
        try {
            switch (command.type()) {
                case SWITCH -> selectTarget(command, true);
                case SEND_TEXT -> {
                    selectTarget(command, false);
                    sessions.sendText(command.text());
                }
                case NEW_CHAT -> sessions.newChat();
                case STOP -> sessions.stop();
                case ATTACH -> sessions.attach(command.attachments());
                case ATTACH_AND_SEND -> sessions.attachAndSend(command.attachments(), command.text());
                case BACK -> sessions.back();
                case FORWARD -> sessions.forward();
                case RELOAD -> sessions.reload();
                case NEXT_PROVIDER -> sessions.nextProvider();
                case PREVIOUS_PROVIDER -> sessions.previousProvider();
            }
            return CommandResult.ok("ok", sessions.currentKey().toString());
        } catch (RuntimeException e) {
            return CommandResult.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * Target-selection semantics are shared by SWITCH and SEND_TEXT:
     *
     * 1. explicit account -> use that exact account and leave workspace mode;
     * 2. explicit workspace -> use its mapping, optionally for an explicit provider;
     * 3. provider only -> switch provider while preserving the active workspace;
     * 4. no target -> stay on the current session.
     */
    private void selectTarget(AiCommand command, boolean requireTargetForSwitch) {
        String provider = clean(command.providerId());
        String account = clean(command.accountId());
        String workspace = clean(command.workspaceId());

        if (account != null) {
            sessions.switchAccount(provider, account);
            return;
        }
        if (workspace != null) {
            if (provider == null) sessions.switchWorkspace(workspace);
            else sessions.switchWorkspace(workspace, provider);
            return;
        }
        if (provider != null) {
            sessions.switchProvider(provider);
            return;
        }
        if (requireTargetForSwitch) {
            throw new IllegalArgumentException("SWITCH requires providerId, accountId or workspaceId");
        }
    }

    private static String clean(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
