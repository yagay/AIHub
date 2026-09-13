package com.yagay.aihub.chromium;

import android.content.Intent;
import android.net.Uri;

import com.yagay.aihub.api.AiHubContract;
import com.yagay.aihub.core.command.AiCommand;
import com.yagay.aihub.core.command.AiCommandType;

import java.util.List;

/** Converts Android intents/deep links/share intents into the same AiCommand used by the app UI. */
public final class AiHubExternalCommandParser {
    private AiHubExternalCommandParser() {}

    public static AiCommand parse(Intent intent, String expectedClientToken) {
        if (intent == null) return null;
        String action = intent.getAction();
        if (Intent.ACTION_SEND.equals(action) && "text/plain".equals(intent.getType())) {
            CharSequence shared = intent.getCharSequenceExtra(Intent.EXTRA_TEXT);
            if (shared != null && !shared.toString().isBlank()) return AiCommand.send(shared.toString());
        }

        if (Intent.ACTION_VIEW.equals(action) && intent.getData() != null) {
            return parseDeepLink(intent.getData(), expectedClientToken);
        }

        if (isPrivateAction(action) && !tokenMatches(
                intent.getStringExtra(AiHubContract.EXTRA_CLIENT_TOKEN), expectedClientToken)) {
            return null;
        }

        String provider = intent.getStringExtra(AiHubContract.EXTRA_PROVIDER_ID);
        String account = intent.getStringExtra(AiHubContract.EXTRA_ACCOUNT_ID);
        String workspace = intent.getStringExtra(AiHubContract.EXTRA_WORKSPACE_ID);

        if (AiHubContract.ACTION_SWITCH.equals(action)) {
            return AiCommand.switchTo(provider, account, workspace);
        }
        if (AiHubContract.ACTION_SEND_TEXT.equals(action)) {
            return new AiCommand(
                    AiCommandType.SEND_TEXT,
                    provider,
                    account,
                    workspace,
                    intent.getStringExtra(AiHubContract.EXTRA_TEXT),
                    List.of());
        }
        if (AiHubContract.ACTION_NEW_CHAT.equals(action)) {
            return new AiCommand(AiCommandType.NEW_CHAT, null, null, null, null, List.of());
        }
        if (AiHubContract.ACTION_STOP.equals(action)) {
            return new AiCommand(AiCommandType.STOP, null, null, null, null, List.of());
        }
        if (AiHubContract.ACTION_NEXT_PROVIDER.equals(action)) {
            return new AiCommand(AiCommandType.NEXT_PROVIDER, null, null, null, null, List.of());
        }
        if (AiHubContract.ACTION_PREVIOUS_PROVIDER.equals(action)) {
            return new AiCommand(AiCommandType.PREVIOUS_PROVIDER, null, null, null, null, List.of());
        }
        return null;
    }

    private static AiCommand parseDeepLink(Uri uri, String expectedClientToken) {
        if (!AiHubContract.DEEP_LINK_SCHEME.equals(uri.getScheme())) return null;
        String command = uri.getHost();
        if (command == null && !uri.getPathSegments().isEmpty()) command = uri.getPathSegments().get(0);
        if (!tokenMatches(uri.getQueryParameter("token"), expectedClientToken)) return null;
        String provider = uri.getQueryParameter("provider");
        String account = uri.getQueryParameter("account");
        String workspace = uri.getQueryParameter("workspace");

        if ("send".equals(command)) {
            return new AiCommand(
                    AiCommandType.SEND_TEXT,
                    provider,
                    account,
                    workspace,
                    uri.getQueryParameter("text"),
                    List.of());
        }
        if ("switch".equals(command)) {
            return AiCommand.switchTo(provider, account, workspace);
        }
        if ("new-chat".equals(command)) {
            return new AiCommand(AiCommandType.NEW_CHAT, null, null, null, null, List.of());
        }
        if ("next".equals(command)) {
            return new AiCommand(AiCommandType.NEXT_PROVIDER, null, null, null, null, List.of());
        }
        if ("previous".equals(command)) {
            return new AiCommand(AiCommandType.PREVIOUS_PROVIDER, null, null, null, null, List.of());
        }
        return null;
    }

    private static boolean isPrivateAction(String action) {
        return AiHubContract.ACTION_SWITCH.equals(action)
                || AiHubContract.ACTION_SEND_TEXT.equals(action)
                || AiHubContract.ACTION_NEW_CHAT.equals(action)
                || AiHubContract.ACTION_STOP.equals(action)
                || AiHubContract.ACTION_NEXT_PROVIDER.equals(action)
                || AiHubContract.ACTION_PREVIOUS_PROVIDER.equals(action);
    }

    private static boolean tokenMatches(String supplied, String expected) {
        return expected != null && !expected.isBlank() && expected.equals(supplied);
    }
}
