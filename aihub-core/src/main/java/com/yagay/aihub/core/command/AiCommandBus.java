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
                case SWITCH -> selectTarget(AiCommandTarget.from(command), true);
                case SEND_TEXT -> {
                    selectTarget(AiCommandTarget.from(command), false);
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

    private void selectTarget(AiCommandTarget target, boolean requireTargetForSwitch) {
        switch (target.mode()) {
            case EXPLICIT_ACCOUNT -> sessions.switchAccount(target.providerId(), target.accountId());
            case EXPLICIT_WORKSPACE -> {
                if (target.providerId() == null) sessions.switchWorkspace(target.workspaceId());
                else sessions.switchWorkspace(target.workspaceId(), target.providerId());
            }
            case PROVIDER -> sessions.switchProvider(target.providerId());
            case CURRENT -> {
                if (requireTargetForSwitch) {
                    throw new IllegalArgumentException("SWITCH requires providerId, accountId or workspaceId");
                }
            }
        }
    }
}
