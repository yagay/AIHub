package com.yagay.aihub.provider;

import com.yagay.aihub.model.ProviderAction;
import com.yagay.aihub.model.ProviderSpec;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

/** Default adapter reused by supported AI chat websites. */
public class GenericWebProviderAdapter implements AiProviderAdapter {
    private final ProviderSpec spec;

    public GenericWebProviderAdapter(ProviderSpec spec) {
        this.spec = spec;
    }

    @Override public ProviderSpec spec() { return spec; }
    @Override public JSONObject probeCommand() { return command("probe", null, null); }
    @Override public JSONObject sendCommand(String text) { return command("send", text, null); }
    @Override public JSONObject newChatCommand() { return command("newChat", null, null); }
    @Override public JSONObject stopCommand() { return command("stop", null, null); }
    @Override public JSONObject attachmentCommand() { return command("attach", null, null); }
    @Override public JSONObject presentationCommand(boolean appMode) { return command("presentation", null, appMode); }

    @Override
    public JSONObject uiActionCommand(String actionId) {
        ProviderAction action = spec.appAction(actionId);
        if (action == null) throw new IllegalArgumentException("Unknown APP action: " + actionId);
        JSONObject command = command("uiAction", null, null);
        try {
            command.put("uiActionId", action.id());
            command.put("actionPick", action.pick());
            command.put("actionSelectors", array(action.selectors()));
            command.put("actionKeywords", array(action.keywords()));
        } catch (Exception error) {
            throw new IllegalStateException("Cannot build APP action " + actionId + " for " + spec.id(), error);
        }
        return command;
    }

    private JSONObject command(String action, String text, Boolean appMode) {
        JSONObject command = new JSONObject();
        JSONObject selectors = new JSONObject();
        try {
            command.put("action", action);
            if (text != null) command.put("text", text);
            if (appMode != null) command.put("appMode", appMode);
            selectors.put("input", array(spec.inputSelectors()));
            selectors.put("send", array(spec.sendSelectors()));
            selectors.put("newChat", array(spec.newChatSelectors()));
            selectors.put("stop", array(spec.stopSelectors()));
            selectors.put("attachment", array(spec.attachmentSelectors()));
            command.put("selectors", selectors);
            command.put("appHide", array(spec.appHideSelectors()));
        } catch (Exception error) {
            throw new IllegalStateException("Cannot build provider command for " + spec.id(), error);
        }
        return command;
    }

    private static JSONArray array(List<String> values) {
        JSONArray out = new JSONArray();
        for (String value : values) out.put(value);
        return out;
    }
}
