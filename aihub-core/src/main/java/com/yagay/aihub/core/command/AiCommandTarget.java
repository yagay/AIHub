package com.yagay.aihub.core.command;

/**
 * Provider/account/workspace routing intent derived from an AiCommand.
 *
 * Priority is intentionally global and shared by every caller:
 * explicit account > explicit workspace > explicit provider > current session.
 */
public record AiCommandTarget(
        Mode mode,
        String providerId,
        String accountId,
        String workspaceId) {

    public enum Mode {
        EXPLICIT_ACCOUNT,
        EXPLICIT_WORKSPACE,
        PROVIDER,
        CURRENT
    }

    public AiCommandTarget {
        if (mode == null) throw new IllegalArgumentException("mode is required");
    }

    public static AiCommandTarget from(AiCommand command) {
        if (command == null) throw new IllegalArgumentException("command is required");
        String provider = clean(command.providerId());
        String account = clean(command.accountId());
        String workspace = clean(command.workspaceId());

        if (account != null) {
            return new AiCommandTarget(Mode.EXPLICIT_ACCOUNT, provider, account, null);
        }
        if (workspace != null) {
            return new AiCommandTarget(Mode.EXPLICIT_WORKSPACE, provider, null, workspace);
        }
        if (provider != null) {
            return new AiCommandTarget(Mode.PROVIDER, provider, null, null);
        }
        return new AiCommandTarget(Mode.CURRENT, null, null, null);
    }

    private static String clean(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
