package com.yagay.aihub.core.command;

import com.yagay.aihub.core.SessionManager;
import java.util.Objects;

public final class AiCommandBus {
    private final SessionManager sessions;

    public AiCommandBus(SessionManager sessions) {
        this.sessions = Objects.requireNonNull(sessions);
    }

    public synchronized CommandResult execute(AiCommand command) {
        try {
            switch (command.type()) {
                case SWITCH -> sessions.activate(
                        command.providerId(), command.accountId(), command.workspaceId());
                case SEND_TEXT -> {
                    if (command.providerId() != null || command.accountId() != null || command.workspaceId() != null) {
                        sessions.activate(command.providerId(), command.accountId(), command.workspaceId());
                    }
                    sessions.sendText(command.text());
                }
                case NEW_CHAT -> sessions.newChat();
                case STOP -> sessions.stop();
                case ATTACH -> sessions.attach(command.attachments());
                case NEXT_PROVIDER -> sessions.nextProvider();
                case PREVIOUS_PROVIDER -> sessions.previousProvider();
            }
            return CommandResult.ok("ok", sessions.currentKey().toString());
        } catch (RuntimeException e) {
            return CommandResult.error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }
}
