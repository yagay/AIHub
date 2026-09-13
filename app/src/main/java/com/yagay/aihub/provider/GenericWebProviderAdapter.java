package com.yagay.aihub.provider;

import com.yagay.aihub.model.ProviderSpec;
import com.yagay.aihub.web.DomBridge;

/** Default adapter reused by normal AI chat websites. */
public class GenericWebProviderAdapter implements AiProviderAdapter {
    private final ProviderSpec spec;

    public GenericWebProviderAdapter(ProviderSpec spec) {
        this.spec = spec;
    }

    @Override public ProviderSpec spec() { return spec; }
    @Override public String sendScript(String text) { return DomBridge.send(spec, text); }
    @Override public String newChatScript() { return DomBridge.newChat(spec); }
    @Override public String stopScript() { return DomBridge.stop(spec); }
    @Override public String attachmentScript() { return DomBridge.attachment(spec); }
    @Override public String conversationScript() { return DomBridge.conversation(spec); }
}
