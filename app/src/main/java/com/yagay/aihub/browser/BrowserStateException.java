package com.yagay.aihub.browser;

public final class BrowserStateException extends Exception {
    private final String code;

    public BrowserStateException(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
