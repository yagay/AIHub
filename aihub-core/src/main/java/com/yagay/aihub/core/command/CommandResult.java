package com.yagay.aihub.core.command;

public record CommandResult(boolean success, String message, String sessionKey) {
    public static CommandResult ok(String message, String sessionKey) {
        return new CommandResult(true, message, sessionKey);
    }
    public static CommandResult error(String message) {
        return new CommandResult(false, message, null);
    }
}
